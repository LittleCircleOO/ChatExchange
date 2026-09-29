package nomathexpectation.chatexchange.image

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import org.apache.logging.log4j.LogManager

//? if >= 26.1 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.minecraft.resources.ResourceLocation
*///?}
//? if >= 26.3 {
/*import net.minecraft.util.Prediction
*///?}
//? if >= 1.21 {
import net.minecraft.core.component.DataComponents
import net.minecraft.world.level.saveddata.maps.MapId
//?}

/**
 * Lazy creation of real map art items (for sharing/keepsake). The first player to
 * request a given image creates the underlying `MapItemSavedData`; identical images
 * (same content hash) share the same map id forever afterwards (in-memory mapping).
 *
 * Maps are created with a non-existent dimension key (the Image2Map approach), so
 * vanilla exploration never repaints the pixels: content is effectively locked.
 */
object MapArt {
    private val logger = LogManager.getLogger("chatexchange")

    private fun generatedDimension(): ResourceKey<Level> {
        //? if >= 26.1 {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("chatexchange", "generated"))
        //?} else {
        /*//? if >= 1.21 {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("chatexchange", "generated"))
        //?} else {
        /*return ResourceKey.create(Registries.DIMENSION, ResourceLocation("chatexchange", "generated"))
        *///?}
        *///?}
    }

    fun giveTo(player: ServerPlayer, entry: ImagePool.Entry) {
        val overworld = player.level().server?.overworld() ?: run {
            logger.warn("No server available for map art creation")
            return
        }

        val mapId = ImagePool.hashToMapId.computeIfAbsent(entry.hash) {
            createMap(overworld, entry.mapColors)
        }

        giveMapItem(player, mapId, entry)
        player.sendSystemMessage(Component.translatable("chatexchange.image.map_art.created"))
    }

    private fun createMap(level: ServerLevel, colors: ByteArray): Any {
        val data = MapItemSavedData.createFresh(0.0, 0.0, 0.toByte(), false, false, generatedDimension())
        System.arraycopy(colors, 0, data.colors, 0, MapColors.MAP_SIZE * MapColors.MAP_SIZE)

        //? if >= 1.21 {
        val id = level.getFreeMapId()
        level.setMapData(id, data)
        return id
        //?} else {
        /*val id = level.getFreeMapId()
        level.setMapData("map_$id", data)
        return id
        *///?}
    }

    private fun giveMapItem(player: ServerPlayer, mapId: Any, entry: ImagePool.Entry) {
        val stack = ItemStack(Items.FILLED_MAP)
        //? if >= 1.21 {
        stack.set(DataComponents.MAP_ID, mapId as MapId)
        //?} else {
        /*stack.getOrCreateTag().putInt("map", mapId as Int)
        *///?}
        if (!player.inventory.add(stack)) {
            //? if >= 26.3 {
            /*player.drop(stack, false, Prediction.SERVER_ONLY)
            *///?} else {
            player.drop(stack, false)
            //?}
        }
    }
}
