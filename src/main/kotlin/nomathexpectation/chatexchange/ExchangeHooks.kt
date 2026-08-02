package nomathexpectation.chatexchange

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

internal object ExchangeHooks {
    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ExchangeServer.startNewInstance(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            ExchangeServer.stopInstance()
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, environment ->
            registerCommands(dispatcher, environment)
        }
    }
}
