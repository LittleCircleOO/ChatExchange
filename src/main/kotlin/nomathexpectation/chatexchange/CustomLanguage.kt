package nomathexpectation.chatexchange

import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.FormattedText
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.CloseableResourceManager
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.StringDecomposer
import net.minecraft.network.chat.Style
import org.apache.logging.log4j.LogManager
import java.util.*

class CustomLanguage(
    private val textMap: Map<String, String>,
    private val defaultRightToLeft: Boolean = false,
) : Language() {
    override fun getOrDefault(key: String, defaultValue: String) = textMap.getOrDefault(key, defaultValue)

    override fun has(id: String) = id in textMap

    override fun isDefaultRightToLeft() = defaultRightToLeft

    override fun getVisualOrder(text: FormattedText) = FormattedCharSequence { sink ->
        text.visit(
            { style, string ->
                if (StringDecomposer.iterateFormatted(string, style, sink)) Optional.empty() else FormattedText.STOP_ITERATION
            },
            Style.EMPTY
        ).isPresent
    }
}

private val logger = LogManager.getLogger(ChatExchange.MOD_ID)

fun languageOf(lang: String, server: MinecraftServer): Language {
    val textMap = mutableMapOf<String, String>()

    fun loadFrom(path: String) {
        CustomLanguage::class.java.getResourceAsStream(path)?.use {
            Language.loadFromJson(it, textMap::put)
        } ?: logger.warn("Unable to load language file $path")
    }

    // vanilla strings bundled with the mod (for resolving translatable components in a chosen language)
    loadFrom("/assets/chatexchange/mclang/$lang.json")

    // other mods' language files
    val langFile = String.format(Locale.ROOT, "lang/%s.json", lang)
    val serverResourceManager = server.resourceManager as? CloseableResourceManager
    if (serverResourceManager != null) {
        val clientResources = MultiPackResourceManager(PackType.CLIENT_RESOURCES, serverResourceManager.listPacks().toList())
        val loaded = clientResources.namespaces.map { namespace ->
            runCatching {
                val langResource = Identifier.fromNamespaceAndPath(namespace, langFile)
                clientResources.getResourceStack(langResource).forEach { resource ->
                    resource.open().use {
                        Language.loadFromJson(it, textMap::put)
                    }
                }
            }.onFailure {
                logger.warn("Skipped language file: {}:{}", namespace, langFile, it)
                return@map 0
            }
            1
        }.sum()
        logger.debug("Loaded {} mod language files for {}", loaded, lang)
    } else {
        logger.warn("Server resource manager is not a CloseableResourceManager; skipped mod language files for {}", lang)
    }

    return CustomLanguage(textMap)
}

fun languageOfOrDefault(lang: String, server: MinecraftServer): Language = runCatching {
    languageOf(lang, server)
}.getOrElse {
    logger.error("Failed to load language: $lang", it)
    Language.getInstance()
}

fun Component.getStringWithLanguage(language: Language): String {
    val current = Language.getInstance()
    Language.inject(language)
    val result = string
    Language.inject(current)
    return result
}

fun Component.toLiteral(language: Language = Language.getInstance()): Component =
    Component.literal(getStringWithLanguage(language))

fun String.toTranslatableComponent(vararg args: Any): MutableComponent = Component.translatable(this, *args)

fun String.toTranslatedLiteral(vararg args: Any, language: Language = Language.getInstance()): Component =
    Component.translatable(this, *args).toLiteral(language)
