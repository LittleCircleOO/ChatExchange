package nomathexpectation.chatexchange.image

import net.fabricmc.loader.api.FabricLoader
import nomathexpectation.chatexchange.ChatExchangeConfig
import org.apache.logging.log4j.LogManager
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Diagnostics for the image -> map pipeline, gated by the `image.debug` config option.
 *
 * While enabled, every processed image is saved under `config/chatexchange-debug/`
 * (raw downloaded bytes plus the produced 128x128 map colors array), and the
 * `/chatexchange image debug` command verifies the runtime palette against the vanilla
 * reference and dumps the cached entries' map color arrays.
 *
 * The vanilla reference values are hard-coded on purpose: base colors and brightness
 * modifiers are identical across every supported version (1.20.1 through 26.3, verified
 * against decompiled sources), so a mismatch proves the running code deviates from the
 * expected pipeline rather than a version difference.
 */
object ImageDebug {
    private val logger = LogManager.getLogger("chatexchange")

    private val debugDir: Path = FabricLoader.getInstance().getConfigDir().resolve("chatexchange-debug")

    /** (r, g, b) -> expected nearest packed id under the vanilla palette, with a hint why. */
    private val probes: List<Triple<IntArray, Int, String>> = listOf(
        Triple(intArrayOf(58, 32, 84), 99, "dark purple background -> COLOR_PURPLE + LOWEST"),
        Triple(intArrayOf(178, 76, 216), 66, "magenta -> COLOR_MAGENTA + HIGH"),
        Triple(intArrayOf(64, 64, 255), 50, "blue -> WATER + HIGH"),
        Triple(intArrayOf(250, 238, 77), 122, "gold -> GOLD + HIGH"),
        Triple(intArrayOf(242, 127, 165), 82, "pink -> COLOR_PINK + HIGH"),
        Triple(intArrayOf(189, 48, 49), 210, "crimson -> CRIMSON_NYLIUM + HIGH"),
        Triple(intArrayOf(255, 255, 255), 34, "white -> SNOW + HIGH"),
        Triple(intArrayOf(0, 0, 0), 119, "black -> COLOR_BLACK + LOWEST"),
    )

    // SHA-256 over packed ids 0..255, 3 bytes (r, g, b) each, 0/0/0 for absent ids (NONE);
    // vanilla reference values, stable across all supported versions.
    private const val VANILLA_PALETTE_SHA256 = "6eb4ddacb87de82e1e72709fa040a1956425e8771ae05c4384f7f48749b4d3e3"

    private val baseNames = arrayOf(
        "NONE", "GRASS", "SAND", "WOOL", "FIRE", "ICE", "METAL", "PLANT", "SNOW", "CLAY", "DIRT", "STONE", "WATER", "WOOD", "QUARTZ",
        "COLOR_ORANGE", "COLOR_MAGENTA", "COLOR_LIGHT_BLUE", "COLOR_YELLOW", "COLOR_LIGHT_GREEN", "COLOR_PINK", "COLOR_GRAY",
        "COLOR_LIGHT_GRAY", "COLOR_CYAN", "COLOR_PURPLE", "COLOR_BLUE", "COLOR_BROWN", "COLOR_GREEN", "COLOR_RED", "COLOR_BLACK",
        "GOLD", "DIAMOND", "LAPIS", "EMERALD", "PODZOL", "NETHER",
        "TERRACOTTA_WHITE", "TERRACOTTA_ORANGE", "TERRACOTTA_MAGENTA", "TERRACOTTA_LIGHT_BLUE", "TERRACOTTA_YELLOW",
        "TERRACOTTA_LIGHT_GREEN", "TERRACOTTA_PINK", "TERRACOTTA_GRAY", "TERRACOTTA_LIGHT_GRAY", "TERRACOTTA_CYAN",
        "TERRACOTTA_PURPLE", "TERRACOTTA_BLUE", "TERRACOTTA_BROWN", "TERRACOTTA_GREEN", "TERRACOTTA_RED", "TERRACOTTA_BLACK",
        "CRIMSON_NYLIUM", "CRIMSON_STEM", "CRIMSON_HYPHAE", "WARPED_NYLIUM", "WARPED_STEM", "WARPED_HYPHAE", "WARPED_WART_BLOCK",
        "DEEPSLATE", "RAW_IRON", "GLOW_LICHEN",
    )
    private val shadeNames = arrayOf("LOW", "NORMAL", "HIGH", "LOWEST")

    @Volatile
    private var lastDecodeInfo: String = "no image decoded yet"

    /** Whether debug helpers are enabled; safe to call before the config has been loaded. */
    fun enabled(): Boolean = runCatching { ChatExchangeConfig.imageDebug.get() }.getOrDefault(false)

    /** Called from [ImagePool.process] while debug is enabled: keeps raw bytes, decode info and produced map colors. */
    fun dumpProcessedImage(bytes: ByteArray, image: BufferedImage, sourceDescription: String, entry: ImagePool.Entry) {
        if (!enabled()) {
            return
        }
        try {
            Files.createDirectories(debugDir)
            val sha = sha256(bytes)
            val base = "download-${sha.take(12)}"
            Files.write(debugDir.resolve("$base.bin"), bytes)
            val meta = buildString {
                appendLine("sha256: $sha")
                appendLine("bytes: ${bytes.size}")
                appendLine("source: $sourceDescription")
                appendLine("decoded: ${image.width}x${image.height} type=${image.type}")
                appendLine("entryId: ${entry.id}")
                appendLine("mapColorsSha256: ${sha256(entry.mapColors)}")
                appendLine("savedAt: ${System.currentTimeMillis()}")
            }
            Files.writeString(debugDir.resolve("$base.txt"), meta)
            Files.write(debugDir.resolve("entry-${entry.id}.bin"), entry.mapColors)
            // Raster evidence: lossless re-encode of the decoded image and of the scaled
            // 128x128 canvas, so decode-vs-scale divergences can be diffed pixel-exact offline.
            ImageIO.write(image, "png", debugDir.resolve("decoded-${sha.take(12)}.png").toFile())
            val canvas = image.scaleToFit(MapColors.MAP_SIZE, MapColors.MAP_SIZE)
            ImageIO.write(canvas, "png", debugDir.resolve("entry-${entry.id}-canvas.png").toFile())
            logger.info("[image-debug] ImageIO reader for this image: {}", readerClassName(bytes))
            lastDecodeInfo = "${image.width}x${image.height} type=${image.type}, ${bytes.size} bytes, sha256=${sha.take(16)}, source=$sourceDescription"
            logger.info(
                "[image-debug] saved {} ({} bytes, decoded {}x{} type={}, entry {} map colors {})",
                "$base.bin", bytes.size, image.width, image.height, image.type, entry.id, debugDir.fileName
            )
        } catch (e: Exception) {
            logger.warn("[image-debug] failed to save processed image artifacts", e)
        }
    }

    /** Class name of the first ImageIO reader that would claim these bytes (the one ImageIO.read uses). */
    private fun readerClassName(bytes: ByteArray): String = runCatching {
        bytes.inputStream().use { input ->
            ImageIO.createImageInputStream(input).use { iis ->
                iis ?: return@runCatching "no ImageInputStream"
                val readers = ImageIO.getImageReaders(iis)
                if (!readers.hasNext()) {
                    return@runCatching "no reader"
                }
                val reader = readers.next()
                "${reader.javaClass.name} [${reader.formatName.firstOrNull()}]"
            }
        }
    }.getOrDefault("unknown")

    /** Runs the full diagnostic, writes a report file and returns a one-line chat summary. */
    fun runDiagnostics(): String {
        val lines = mutableListOf<String>()
        lines += "jvm: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} vendor=${System.getProperty("java.vendor")}"
        lines += "java.awt.headless: ${System.getProperty("java.awt.headless")}"
        lines += "debugDir: $debugDir"
        lines += "lastDecode: $lastDecodeInfo"

        val tableSha = paletteSha256()
        val paletteOk = tableSha == VANILLA_PALETTE_SHA256
        lines += "palette sha256: $tableSha"
        lines += "vanilla reference: $VANILLA_PALETTE_SHA256 (${if (paletteOk) "MATCH" else "MISMATCH"})"

        var pass = 0
        for ((rgb, expected, hint) in probes) {
            val actual = MapColors.matchColor(rgb[0], rgb[1], rgb[2]).toInt() and 0xFF
            val ok = actual == expected
            if (ok) pass++
            val row = MapColors.row(actual)
            val rowText = row?.contentToString() ?: "absent"
            lines += "probe (${rgb[0]},${rgb[1]},${rgb[2]}) -> $actual (${packedName(actual)}), expected $expected: ${if (ok) "PASS" else "FAIL"}; matched row $rowText; $hint"
        }

        lines += ""
        lines += "== full palette table (packed: name+shade = r,g,b) =="
        for (packed in 0..255) {
            val row = MapColors.row(packed) ?: continue
            lines += String.format("%3d: %-28s = %3d,%3d,%3d", packed, packedName(packed), row[1], row[2], row[3])
        }

        lines += ""
        lines += "== cached entries (ImagePool LRU) =="
        val entries = ImagePool.recentEntries()
        if (entries.isEmpty()) {
            lines += "(empty)"
        }
        for (entry in entries) {
            lines += "entry ${entry.id}: hash=${entry.hash.take(12)} mapColorsSha256=${sha256(entry.mapColors).take(16)}"
            val histogram = HashMap<Byte, Int>()
            for (b in entry.mapColors) {
                histogram.merge(b, 1, Int::plus)
            }
            for ((b, count) in histogram.entries.sortedByDescending { it.value }.take(6)) {
                lines += String.format("  [%3d] %-28s x%d", b.toInt() and 0xFF, packedName(b.toInt() and 0xFF), count)
            }
            ImagePool.hashToMapId[entry.hash]?.let { lines += "  real map id: $it" }
        }

        Files.createDirectories(debugDir)
        val report = debugDir.resolve("debug-report-${System.currentTimeMillis()}.txt")
        Files.writeString(report, lines.joinToString("\n"))
        val paletteLine = if (paletteOk) "palette matches vanilla" else "palette MISMATCHES vanilla"
        val probeSummary = "$pass/${probes.size} probes PASS"
        logger.info("[image-debug] {}, {}, report: {}", paletteLine, probeSummary, report.toAbsolutePath())
        return "$paletteLine; $probeSummary; report: ${report.fileName}"
    }

    private fun packedName(packed: Int): String {
        val base = packed shr 2
        val shade = packed and 3
        if (base == 0) {
            return "NONE"
        }
        val name = if (base < baseNames.size) baseNames[base] else "INVALID_$base"
        return "$name+${shadeNames[shade]}"
    }

    /** SHA-256 over packed ids 0..255, the same serialization as the vanilla reference constant. */
    private fun paletteSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (packed in 0..255) {
            val row = MapColors.row(packed)
            digest.update((row?.get(1) ?: 0).toByte())
            digest.update((row?.get(2) ?: 0).toByte())
            digest.update((row?.get(3) ?: 0).toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
