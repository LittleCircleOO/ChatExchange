package nomathexpectation.chatexchange

import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.*

data class ChatExchangeData(
    private val ignoredPlayers: MutableSet<UUID> = mutableSetOf()
) : SavedData() {
    fun addIgnoredPlayer(player: UUID) {
        ignoredPlayers += player
        setDirty()
    }

    fun removeIgnoredPlayer(player: UUID) {
        ignoredPlayers -= player
        setDirty()
    }

    fun isIgnoredPlayer(player: UUID): Boolean {
        return player in ignoredPlayers
    }

    companion object {
        const val DATA_STORAGE_KEY = "chatexchange_data"

        // Note: DataFixTypes is required by vanilla SavedDataType but is functionally inert for
        // brand-new mod data (no DFU history; same-version reads are a no-op). SAVED_DATA_COMMAND_STORAGE
        // is a generic placeholder here; revisit if cross-version save migration ever becomes needed.
        val TYPE = SavedDataType(
            Identifier.fromNamespaceAndPath(ChatExchange.MOD_ID, DATA_STORAGE_KEY),
            ::ChatExchangeData,
            RecordCodecBuilder.create { instance ->
                instance.group(
                    UUIDUtil.CODEC_SET.fieldOf(ChatExchangeData::ignoredPlayers.name).forGetter(ChatExchangeData::ignoredPlayers),
                ).apply(instance, ::ChatExchangeData)
            },
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE,
        )
    }
}

val MinecraftServer.chatExchangeData: ChatExchangeData
    get() = dataStorage.computeIfAbsent(ChatExchangeData.TYPE)
