package nomathexpectation.chatexchange.image

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.phys.Vec3
import nomathexpectation.chatexchange.ChatExchangeConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

//? if >= 1.21 {
import net.minecraft.core.component.DataComponents
import net.minecraft.world.level.saveddata.maps.MapId
//?}

/**
 * Pure-server-side high-resolution preview: spawns a client-only invisible item frame
 * in front of the player holding a filled map with a virtual (fake) map id, then
 * streams the map pixel data packet. The vanilla client renders map textures for any
 * id it receives, so no `map_<id>.dat` is ever written and no real map id is consumed.
 * The display auto-despawns after the configured timeout.
 */
object VirtualMapDisplay {
    /** Virtual ids live far above the real map id space; they are never persisted server-side. */
    private const val FAKE_ID_BASE = 0x40000000

    private val fakeIdCounter = AtomicInteger(0)
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class Session(val entityId: Int, val timeoutJob: Job)

    fun show(player: ServerPlayer, entry: ImagePool.Entry) {
        destroy(player)

        val eye = player.eyePosition
        val look = player.getViewVector(1.0f)
        // Only the horizontal part of the view vector drives placement: the anchor
        // block's Y must always come from the (pose-aware) eye position, otherwise
        // any downward pitch of ~15° or more sinks the frame into the feet-level
        // block and the preview reads as "on the ground" instead of face height.
        var forwardX = look.x.toDouble()
        var forwardZ = look.z.toDouble()
        val horizontalLength = Math.sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (horizontalLength < 1.0E-4) {
            // Looking straight up/down: fall back to the body yaw so the preview
            // still spawns in front of the player instead of on top of them.
            val yaw = Math.toRadians(player.getYRot().toDouble())
            forwardX = -Math.sin(yaw)
            forwardZ = Math.cos(yaw)
        } else {
            forwardX /= horizontalLength
            forwardZ /= horizontalLength
        }
        val center = Vec3(eye.x + forwardX * 2.5, eye.y, eye.z + forwardZ * 2.5)

        val facing = if (kotlin.math.abs(forwardX) > kotlin.math.abs(forwardZ)) {
            if (forwardX > 0) Direction.WEST else Direction.EAST
        } else {
            if (forwardZ > 0) Direction.NORTH else Direction.SOUTH
        }

        val fakeId = FAKE_ID_BASE + fakeIdCounter.incrementAndGet()
        val frame = ItemFrame(player.level(), BlockPos.containing(center.x, center.y, center.z), facing)
        frame.setPosRaw(center.x, center.y, center.z)
        frame.setInvisible(true)
        frame.setItem(fakeMapStack(fakeId), false)

        val addPacket = ClientboundAddEntityPacket(
            frame.id,
            frame.uuid,
            center.x,
            center.y,
            center.z,
            0.0f,
            0.0f,
            frame.type,
            facing.get3DDataValue(),
            Vec3.ZERO,
            0.0,
        )
        val dataPacket = ClientboundSetEntityDataPacket(
            frame.id,
            frame.entityData.getNonDefaultValues() ?: listOf(),
        )

        val mapPacket = mapDataPacket(fakeId, entry.mapColors)

        player.connection.send(addPacket)
        player.connection.send(dataPacket)
        player.connection.send(mapPacket)

        val timeoutJob = scope.launch {
            delay(ChatExchangeConfig.imageVirtualPreviewSeconds.get().coerceAtLeast(1).seconds)
            destroy(player)
        }
        sessions[player.uuid] = Session(frame.id, timeoutJob)
    }

    fun destroy(player: ServerPlayer) {
        val session = sessions.remove(player.uuid) ?: return
        session.timeoutJob.cancel()
        kotlin.runCatching {
            player.connection.send(ClientboundRemoveEntitiesPacket(session.entityId))
        }
    }

    fun shutdown() {
        scope.cancel()
        sessions.clear()
    }

    private fun fakeMapStack(fakeId: Int): ItemStack {
        val stack = ItemStack(Items.FILLED_MAP)
        //? if >= 1.21 {
        stack.set(DataComponents.MAP_ID, MapId(fakeId))
        //?} else {
        /*stack.getOrCreateTag().putInt("map", fakeId)
        *///?}
        return stack
    }

    private fun mapDataPacket(fakeId: Int, colors: ByteArray): ClientboundMapItemDataPacket {
        val patch = MapItemSavedData.MapPatch(0, 0, MapColors.MAP_SIZE, MapColors.MAP_SIZE, colors)
        //? if >= 1.21 {
        return ClientboundMapItemDataPacket(MapId(fakeId), 0.toByte(), false, null, patch)
        //?} else {
        /*return ClientboundMapItemDataPacket(fakeId, 0.toByte(), false, null, patch)
        *///?}
    }
}
