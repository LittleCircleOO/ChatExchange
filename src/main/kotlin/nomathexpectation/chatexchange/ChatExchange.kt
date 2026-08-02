package nomathexpectation.chatexchange

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object ChatExchange : ModInitializer {
    const val MOD_ID = "chatexchange"

    private val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LOGGER.info("Hello! This is working!")

        ChatExchangeConfig.register()
        ExchangeHooks.register()
    }
}
