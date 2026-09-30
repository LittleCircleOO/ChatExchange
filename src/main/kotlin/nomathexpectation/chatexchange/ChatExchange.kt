package nomathexpectation.chatexchange

import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import nomathexpectation.chatexchange.convert.Nicknames
import org.slf4j.LoggerFactory
import java.nio.file.Files

object ChatExchange : ModInitializer {
    const val MOD_ID = "chatexchange"

    private val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LOGGER.info("Hello! This is working!")

        ChatExchangeConfig.register()
        registerChatExchangePlaceholders()
        ExchangeHooks.register()
        Nicknames.prepare(FabricLoader.getInstance().getConfigDir())
        createResourcePacksDir()
    }

    private fun createResourcePacksDir() {
        runCatching {
            Files.createDirectories(resourcePacksDir)
            val readme = resourcePacksDir.resolve("README.txt")
            if (Files.notExists(readme)) {
                Files.writeString(
                    readme,
                    """
                    Place resource packs here to provide or override translations used when ChatExchange
                    forwards messages (chat / join / leave / death / advancement) to external clients.

                    - Each entry can be a .zip file or a folder. No pack.mcmeta is required.
                    - Only files at assets/<namespace>/lang/<locale>.json are read.
                    - The locale comes from the `language` option in config/chatexchange-common.toml.
                    - These translations have the highest priority: they override bundled (mclang)
                      and server pack translations on key conflicts.
                    - Entries are processed in name order; on conflicts the later entry wins.
                    - Restart the server to apply changes.
                    """.trimIndent() + System.lineSeparator()
                )
            }
        }.onFailure {
            LOGGER.warn("Failed to prepare {}", resourcePacksDir, it)
        }
    }
}
