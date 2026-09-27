package nomathexpectation.chatexchange

import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.*

/**
 * 1.20.1 variant of the broadcast-opt-out storage.
 *
 * Uses the 1.20.1 SavedData model: NBT (de)serialization via load/create functions and
 * `server.overworld().dataStorage.computeIfAbsent(load, create, name)`.
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

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        ignoredPlayers.forEach { list.add(IntArrayTag(UUIDUtil.uuidToIntArray(it))) }
        tag.put(IGNORED_PLAYERS_KEY, list)
        return tag
    }

    companion object {
        const val DATA_STORAGE_KEY = "chatexchange_data"
        private const val IGNORED_PLAYERS_KEY = "ignoredPlayers"

        internal fun load(tag: CompoundTag): ChatExchangeData {
            val set = mutableSetOf<UUID>()
            tag.getList(IGNORED_PLAYERS_KEY, Tag.TAG_INT_ARRAY.toInt()).forEach { entry ->
                if (entry is IntArrayTag) {
                    set += UUIDUtil.uuidFromIntArray(entry.asIntArray)
                }
            }
            return ChatExchangeData(set)
        }
    }
}

val MinecraftServer.chatExchangeData: ChatExchangeData
    get() = overworld().dataStorage.computeIfAbsent(
        ChatExchangeData.Companion::load,
        ::ChatExchangeData,
        ChatExchangeData.DATA_STORAGE_KEY,
    )
