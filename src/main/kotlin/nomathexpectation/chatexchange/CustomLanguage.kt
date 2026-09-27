package nomathexpectation.chatexchange

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.FormattedText
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.CloseableResourceManager
import net.minecraft.server.packs.resources.MultiPackResourceManager
//? if >= 26.1 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.minecraft.resources.ResourceLocation
*///?}
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.StringDecomposer
import net.minecraft.network.chat.Style
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.zip.ZipFile

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

/**
 * Admin override packs for external-forwarding localization, at `config/chatexchange_resourcepacks/`.
 * See [loadResourcePackOverrides]; the directory (with a README) is created in [ChatExchange.onInitialize].
 */
val resourcePacksDir: Path = FabricLoader.getInstance().getConfigDir().resolve("${ChatExchange.MOD_ID}_resourcepacks")

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
                //? if >= 26.1 {
                val langResource = Identifier.fromNamespaceAndPath(namespace, langFile)
                //?} else {
                /*//? if >= 1.21 {
                val langResource = ResourceLocation.fromNamespaceAndPath(namespace, langFile)
                //?} else {
                /*val langResource = ResourceLocation(namespace, langFile)
                *///?}
                *///?}
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
        logger.info("Loaded {} server/mod language file(s) for {}", loaded, lang)
    } else {
        logger.warn("Server resource manager is not a CloseableResourceManager; skipped mod language files for {}", lang)
    }

    // admin override packs from config/chatexchange_resourcepacks (highest priority, loaded last)
    loadResourcePackOverrides(lang, textMap)

    return CustomLanguage(textMap)
}

fun languageOfOrDefault(lang: String, server: MinecraftServer): Language = runCatching {
    languageOf(lang, server)
}.getOrElse {
    logger.error("Failed to load language: $lang", it)
    Language.getInstance()
}

/**
 * Loads admin-provided override packs from [resourcePacksDir] into [textMap].
 *
 * Each entry may be a `.zip` file or a folder; no `pack.mcmeta` is required. Only
 * `assets/<namespace>/lang/<locale>.json` files matching the target locale are read.
 * Called last by [languageOf], so overrides win over mclang and server-pack translations.
 * Entries are processed in name order; on key conflicts the later entry wins.
 *
 * Always logs the scan outcome (loaded / scanned-but-missed / empty / missing) at info level.
 */
private fun loadResourcePackOverrides(lang: String, textMap: MutableMap<String, String>) {
    if (!Files.isDirectory(resourcePacksDir)) {
        logger.info("Admin override directory config/{} does not exist; no override translations loaded.", resourcePacksDir.fileName)
        return
    }

    val filePattern = "^[^/]+/lang/${Regex.escape(lang)}\\.json$".toRegex()
    val loadedByPack = LinkedHashMap<String, Int>()
    var scannedPacks = 0
    var failedFiles = 0

    fun countLoaded(packName: String) {
        loadedByPack.merge(packName, 1, Int::plus)
    }

    Files.list(resourcePacksDir).use { it.sorted().toList() }.forEach { entry ->
        val name = entry.fileName.toString()

        if (Files.isDirectory(entry)) {
            scannedPacks++
            val assetsDir = entry.resolve("assets")
            if (!Files.isDirectory(assetsDir)) {
                return@forEach
            }

            Files.walk(assetsDir).use { walk ->
                walk.filter { Files.isRegularFile(it) }.forEach { file ->
                    val relative = assetsDir.relativize(file).toString().replace('\\', '/')
                    if (!filePattern.matches(relative)) {
                        return@forEach
                    }

                    runCatching {
                        Files.newInputStream(file, StandardOpenOption.READ).use {
                            Language.loadFromJson(it, textMap::put)
                        }
                        countLoaded(name)
                    }.onFailure {
                        failedFiles++
                        logger.warn("Failed to load override language file {} from {}", relative, name, it)
                    }
                }
            }
        } else if (name.endsWith(".zip", ignoreCase = true)) {
            scannedPacks++
            runCatching {
                ZipFile(entry.toFile()).use { zip ->
                    zip.entries().toList().forEach { zipEntry ->
                        if (zipEntry.isDirectory || !zipEntry.name.startsWith("assets/")) {
                            return@forEach
                        }

                        val relative = zipEntry.name.removePrefix("assets/")
                        if (!filePattern.matches(relative)) {
                            return@forEach
                        }

                        runCatching {
                            zip.getInputStream(zipEntry).use {
                                Language.loadFromJson(it, textMap::put)
                            }
                            countLoaded(name)
                        }.onFailure {
                            failedFiles++
                            logger.warn("Failed to load override language file {} from {}", zipEntry.name, name, it)
                        }
                    }
                }
            }.onFailure {
                logger.warn("Failed to read override pack {}", name, it)
            }
        }
    }

    val total = loadedByPack.values.sum()
    when {
        total > 0 -> logger.info(
            "Loaded {} override language file(s) for {} from config/{} [{}]{}",
            total, lang, resourcePacksDir.fileName,
            loadedByPack.entries.joinToString { "${it.key}: ${it.value}" },
            if (failedFiles > 0) " ($failedFiles file(s) failed to parse)" else ""
        )
        scannedPacks > 0 -> logger.info(
            "Scanned {} pack(s) in config/{} but none contained assets/<namespace>/lang/{}.json.",
            scannedPacks, resourcePacksDir.fileName, lang
        )
        else -> logger.info(
            "No override packs (.zip or folder) found in config/{}; no override translations loaded.",
            resourcePacksDir.fileName
        )
    }
}

fun Component.getStringWithLanguage(language: Language): String {
    val current = Language.getInstance()
    Language.inject(language)
    val result = string
    Language.inject(current)
    return result
}

fun String.toTranslatableComponent(vararg args: Any): MutableComponent = Component.translatable(this, *args)
