package nomathexpectation.chatexchange

import net.fabricmc.loader.api.FabricLoader

internal val chatImageLoaded
    get() = FabricLoader.getInstance().isModLoaded("chatimage")

suspend fun String.tryParseCICodeFileToData(): String {
    if (!chatImageLoaded) {
        return this
    }
    return tryParseCICodeFileToData0(this)
}
