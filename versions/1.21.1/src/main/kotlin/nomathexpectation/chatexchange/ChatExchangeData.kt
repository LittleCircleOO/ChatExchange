package nomathexpectation.chatexchange

import net.minecraft.core.HolderLookup
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import java.util.*

/**
 * 1.21.1 variant of the broadcast-opt-out storage.
 *
 * Uses the 1.20.2+ SavedData model: `SavedData.Factory` with a registries-aware deserializer and
 * `server.overworld().dataStorage.computeIfAbsent(factory, name)`.
 * UUIDs are stored as NBT int arrays (`UUIDUtil.uuidToIntArray`).
 */
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

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        ignoredPlayers.forEach { list.add(IntArrayTag(UUIDUtil.uuidToIntArray(it))) }
        tag.put(IGNORED_PLAYERS_KEY, list)
        return tag
    }

    companion object {
        const val DATA_STORAGE_KEY = "chatexchange_data"
        private const val IGNORED_PLAYERS_KEY = "ignoredPlayers"

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): ChatExchangeData {
            val set = mutableSetOf<UUID>()
            tag.getList(IGNORED_PLAYERS_KEY, Tag.TAG_INT_ARRAY.toInt()).forEach { entry ->
                if (entry is IntArrayTag) {
                    set += UUIDUtil.uuidFromIntArray(entry.asIntArray)
                }
            }
            return ChatExchangeData(set)
        }

        fun factory() = SavedData.Factory(
            ::ChatExchangeData,
            ::load,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE,
        )
    }
}

val MinecraftServer.chatExchangeData: ChatExchangeData
    get() = overworld().dataStorage.computeIfAbsent(
        ChatExchangeData.factory(),
        ChatExchangeData.DATA_STORAGE_KEY,
    )
