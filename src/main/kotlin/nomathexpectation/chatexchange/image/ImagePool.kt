package nomathexpectation.chatexchange.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.network.chat.Component
import nomathexpectation.chatexchange.ChatExchangeConfig
import org.apache.logging.log4j.LogManager
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Downloads/decodes images referenced by inbound messages and prepares all render
 * artifacts (hover ASCII art, dialog ASCII art, 128x128 map palette indices).
 * Entries are kept in an LRU cache addressable by a short numeric id used by the
 * interaction commands.
 */
object ImagePool {
    private val logger = LogManager.getLogger("chatexchange")

    class Entry(
        val id: Int,
        val hash: String,
        val hoverArt: Component,
        val dialogArt: Component,
        val dialogArtWidthChars: Int,
        val mapColors: ByteArray,
    )

    private val nextId = AtomicInteger(0)
    private val lock = Any()
    private val entries = LinkedHashMap<Int, Entry>(16, 0.75f, true)

    /** image hash -> created real map id (type differs per version), shared by [MapArt]. */
    val hashToMapId = ConcurrentHashMap<String, Any>()

    fun get(id: Int): Entry? = synchronized(lock) { entries[id] }

    private fun put(entry: Entry) {
        val capacity = ChatExchangeConfig.imageCacheSize.get().coerceAtLeast(1)
        synchronized(lock) {
            entries[entry.id] = entry
            while (entries.size > capacity) {
                val eldest = entries.keys.first()
                entries.remove(eldest)
            }
        }
    }

    /**
     * Builds the in-game message body from raw external content: image CICodes are
     * replaced with interactive placeholders, everything else is kept as literal text.
     * Must be called from a coroutine (network + decoding run on [Dispatchers.IO]).
     */
    suspend fun buildMessage(content: String): Component {
        if (!ChatExchangeConfig.imageEnabled.get()) {
            return Component.literal(content)
        }

        val segments = CICode.split(content)
        if (segments.none { it is CICode.Segment.Image }) {
            return Component.literal(content)
        }

        val root = Component.literal("")
        for (segment in segments) {
            when (segment) {
                is CICode.Segment.Text -> root.append(Component.literal(segment.text))
                is CICode.Segment.Image -> {
                    val entry = acquire(segment)
                    if (entry != null) {
                        root.append(ImagePlaceholder.build(entry))
                    } else {
                        // Keep the raw code visible so the message is not silently lost.
                        root.append(Component.literal(segment.raw))
                    }
                }
            }
        }
        return root
    }

    private suspend fun acquire(segment: CICode.Segment.Image): Entry? {
        val source = segment.source ?: return null

        val bytes = when (source) {
            is CICode.ImageSource.Http -> download(source.url) ?: return null
            is CICode.ImageSource.Inline -> decodeBase64(source.base64) ?: return null
        }

        return process(bytes)
    }

    private fun process(bytes: ByteArray): Entry? {
        val image = try {
            ImageIO.read(bytes.inputStream()) // GIF: first frame
        } catch (e: Exception) {
            logger.warn("Failed to decode image", e)
            null
        } ?: return null

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val hover = AsciiArt.render(
            image,
            ChatExchangeConfig.imageHoverPreviewWidth.get().coerceAtLeast(1),
            ChatExchangeConfig.imageHoverPreviewHeight.get().coerceAtLeast(1),
        )
        val dialog = AsciiArt.render(
            image,
            ChatExchangeConfig.imageDialogPreviewWidth.get().coerceAtLeast(1),
            ChatExchangeConfig.imageDialogPreviewHeight.get().coerceAtLeast(1),
        )
        val entry = Entry(
            id = nextId.incrementAndGet(),
            hash = hash,
            hoverArt = hover.component,
            dialogArt = dialog.component,
            dialogArtWidthChars = dialog.widthChars,
            mapColors = image.toMapColors(),
        )
        put(entry)
        return entry
    }

    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!isUrlAllowed(url)) {
                logger.warn("Rejected image url not matching the allowlist: {}", url)
                return@withContext null
            }

            val timeout = ChatExchangeConfig.imageDownloadTimeoutSeconds.get().coerceAtLeast(1) * 1000
            val limit = ChatExchangeConfig.imageMaxDownloadBytes.get().coerceAtLeast(1)

            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.setRequestProperty("User-Agent", "ChatExchange")
            connection.getInputStream().use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) {
                        break
                    }
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > limit) {
                        logger.warn("Image from {} exceeds the size limit", url)
                        return@withContext null
                    }
                }
                buffer.toByteArray()
            }
        } catch (e: Exception) {
            logger.warn("Failed to download image from {}", url, e)
            null
        }
    }

    private fun decodeBase64(data: String): ByteArray? {
        return try {
            val bytes = Base64.getDecoder().decode(data)
            if (bytes.size > ChatExchangeConfig.imageMaxDownloadBytes.get()) {
                logger.warn("Inline image exceeds the size limit")
                null
            } else {
                bytes
            }
        } catch (e: Exception) {
            logger.warn("Failed to decode inline image", e)
            null
        }
    }

    private fun isUrlAllowed(url: String): Boolean {
        val allowlist = ChatExchangeConfig.imageUrlAllowlist.get().trim()
        if (allowlist.isEmpty()) {
            return true
        }

        val host = try {
            URI(url).host ?: return false
        } catch (e: Exception) {
            return false
        }

        return allowlist.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .any { host == it || host.endsWith(".$it") }
    }
}
