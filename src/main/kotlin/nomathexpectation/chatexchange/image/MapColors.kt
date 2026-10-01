package nomathexpectation.chatexchange.image

import net.minecraft.world.level.material.MapColor
import java.awt.image.BufferedImage

/**
 * Scales the image into a 128x128 canvas (aspect-preserving, centered, transparent padding)
 * and converts it into vanilla map palette indices with Floyd-Steinberg dithering.
 */
fun BufferedImage.toMapColors(): ByteArray {
    val scaled = scaleToFit(MapColors.MAP_SIZE, MapColors.MAP_SIZE)
    val size = MapColors.MAP_SIZE
    val result = ByteArray(size * size) // 0 = NONE = transparent
    val errors = Array(size) { DoubleArray(size * 3) } // per-pixel dithering error (r,g,b)

    for (y in 0 until size) {
        for (x in 0 until size) {
            val pixel = scaled.getRGB(x, y)
            val alpha = pixel ushr 24
            if (alpha < 128) {
                continue // stays index 0 (transparent)
            }

            var r = (pixel shr 16 and 0xFF) + errors[x][y * 3]
            var g = (pixel shr 8 and 0xFF) + errors[x][y * 3 + 1]
            var b = (pixel and 0xFF) + errors[x][y * 3 + 2]
            r = r.coerceIn(0.0, 255.0)
            g = g.coerceIn(0.0, 255.0)
            b = b.coerceIn(0.0, 255.0)

            val index = MapColors.matchColor(r.toInt(), g.toInt(), b.toInt())
            result[x + y * size] = index

            val matched = MapColors.paletteEntryOf(index)
            val er = r - matched[0]
            val eg = g - matched[1]
            val eb = b - matched[2]

            fun diffuse(dx: Int, dy: Int, factor: Double) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
                    return
                }
                errors[nx][ny * 3] += er * factor
                errors[nx][ny * 3 + 1] += eg * factor
                errors[nx][ny * 3 + 2] += eb * factor
            }

            diffuse(1, 0, 7.0 / 16.0)
            diffuse(-1, 1, 3.0 / 16.0)
            diffuse(0, 1, 5.0 / 16.0)
            diffuse(1, 1, 1.0 / 16.0)
        }
    }

    return result
}

/**
 * Scales the image into a [maxWidth] x [maxHeight] canvas (aspect-preserving, centered, transparent
 * padding) using a self-contained exact-window box filter.
 *
 * Deliberately avoids [BufferedImage.getScaledInstance]: SCALE_SMOOTH routes through the
 * ToolkitImage/AreaAveragingScaleFilter machinery, whose output has proven environment-dependent
 * on modded servers (deterministically wrong rasters with identical input, palette and code),
 * while SCALE_DEFAULT (plain replication, the Image2Map approach) is too crude for single-map art.
 * Integer box averaging is deterministic on every JVM, thread and classpath.
 */
fun BufferedImage.scaleToFit(maxWidth: Int, maxHeight: Int): BufferedImage {
    var newWidth = width
    var newHeight = height
    if (newWidth > maxWidth) {
        newHeight = newHeight * maxWidth / newWidth
        newWidth = maxWidth
    }
    if (newHeight > maxHeight) {
        // Compute before overwriting newHeight, otherwise tall/skinny images get stretched to full width.
        newWidth = newWidth * maxHeight / newHeight
        newHeight = maxHeight
    }
    if (newWidth <= 0 || newHeight <= 0) {
        newWidth = 1
        newHeight = 1
    }

    val src = getRGB(0, 0, width, height, null, 0, width)
    val dst = IntArray(maxWidth * maxHeight)
    val offsetX = (maxWidth - newWidth) / 2
    val offsetY = (maxHeight - newHeight) / 2

    for (ty in 0 until newHeight) {
        val sy0 = (ty.toLong() * height / newHeight).toInt()
        var sy1 = ((ty + 1).toLong() * height / newHeight).toInt()
        if (sy1 <= sy0) {
            sy1 = sy0 + 1 // upscaling axis: window degenerates to the nearest source row
        }
        for (tx in 0 until newWidth) {
            val sx0 = (tx.toLong() * width / newWidth).toInt()
            var sx1 = ((tx + 1).toLong() * width / newWidth).toInt()
            if (sx1 <= sx0) {
                sx1 = sx0 + 1 // upscaling axis: window degenerates to the nearest source column
            }

            var sa = 0L
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var count = 0L
            var sy = sy0
            while (sy < sy1) {
                var sx = sx0
                while (sx < sx1) {
                    val p = src[sx + sy * width]
                    sa += (p ushr 24) and 0xFF
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                    count++
                    sx++
                }
                sy++
            }

            dst[offsetX + tx + (offsetY + ty) * maxWidth] =
                (((sa + count / 2) / count).toInt() shl 24) or
                    (((sr + count / 2) / count).toInt() shl 16) or
                    (((sg + count / 2) / count).toInt() shl 8) or
                    ((sb + count / 2) / count).toInt()
        }
    }

    val output = BufferedImage(maxWidth, maxHeight, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, maxWidth, maxHeight, dst, 0, maxWidth)
    return output
}

/**
 * Vanilla map palette utilities. Packed index = baseId << 2 | brightnessId; brightness
 * modifiers are vanilla constants (LOWEST/LOW/NORMAL/HIGH = 135/180/220/255), identical
 * across all supported versions. Base colors are referenced from vanilla [MapColor]
 * constants (stable since 1.8, verified identical between 1.20.1 and 26.3).
 */
object MapColors {
    const val MAP_SIZE = 128

    private val BASES: Array<MapColor> = arrayOf(
        MapColor.GRASS, MapColor.SAND, MapColor.WOOL, MapColor.FIRE, MapColor.ICE,
        MapColor.METAL, MapColor.PLANT, MapColor.SNOW, MapColor.CLAY, MapColor.DIRT,
        MapColor.STONE, MapColor.WATER, MapColor.WOOD, MapColor.QUARTZ,
        MapColor.COLOR_ORANGE, MapColor.COLOR_MAGENTA, MapColor.COLOR_LIGHT_BLUE, MapColor.COLOR_YELLOW,
        MapColor.COLOR_LIGHT_GREEN, MapColor.COLOR_PINK, MapColor.COLOR_GRAY, MapColor.COLOR_LIGHT_GRAY,
        MapColor.COLOR_CYAN, MapColor.COLOR_PURPLE, MapColor.COLOR_BLUE, MapColor.COLOR_BROWN,
        MapColor.COLOR_GREEN, MapColor.COLOR_RED, MapColor.COLOR_BLACK,
        MapColor.GOLD, MapColor.DIAMOND, MapColor.LAPIS, MapColor.EMERALD,
        MapColor.PODZOL, MapColor.NETHER,
        MapColor.TERRACOTTA_WHITE, MapColor.TERRACOTTA_ORANGE, MapColor.TERRACOTTA_MAGENTA,
        MapColor.TERRACOTTA_LIGHT_BLUE, MapColor.TERRACOTTA_YELLOW, MapColor.TERRACOTTA_LIGHT_GREEN,
        MapColor.TERRACOTTA_PINK, MapColor.TERRACOTTA_GRAY, MapColor.TERRACOTTA_LIGHT_GRAY,
        MapColor.TERRACOTTA_CYAN, MapColor.TERRACOTTA_PURPLE, MapColor.TERRACOTTA_BLUE,
        MapColor.TERRACOTTA_BROWN, MapColor.TERRACOTTA_GREEN, MapColor.TERRACOTTA_RED,
        MapColor.TERRACOTTA_BLACK,
        MapColor.CRIMSON_NYLIUM, MapColor.CRIMSON_STEM, MapColor.CRIMSON_HYPHAE,
        MapColor.WARPED_NYLIUM, MapColor.WARPED_STEM, MapColor.WARPED_HYPHAE, MapColor.WARPED_WART_BLOCK,
        MapColor.DEEPSLATE, MapColor.RAW_IRON, MapColor.GLOW_LICHEN,
    )

    // [index, r, g, b] rows for every base color x brightness combination.
    // getPackedId returns a signed byte; & 0xFF keeps indices in the 0-255 range.
    private val PALETTE: Array<IntArray> = buildList {
        for (base in BASES) {
            for (brightness in enumValues<MapColor.Brightness>()) {
                val rgb = base.rgbAt(brightness)
                add(intArrayOf(base.getPackedId(brightness).toInt() and 0xFF, rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF))
            }
        }
    }.toTypedArray()

    // Direct lookup table: packed index (0-255) -> palette row.
    private val BY_INDEX: Array<IntArray?> = arrayOfNulls<IntArray>(256).also { table ->
        for (entry in PALETTE) {
            table[entry[0]] = entry
        }
    }

    private fun MapColor.rgbAt(brightness: MapColor.Brightness): Int {
        //? if >= 26.1 {
        return this.calculateARGBColor(brightness) and 0xFFFFFF
        //?} else {
        /*return this.calculateRGBColor(brightness) and 0xFFFFFF
        *///?}
    }

    /** Finds the palette index closest to the given opaque RGB color (unweighted squared distance, as used by map-canvas-api). */
    fun matchColor(r: Int, g: Int, b: Int): Byte {
        var best = PALETTE[0]
        var bestDist = Int.MAX_VALUE
        for (entry in PALETTE) {
            val dr = r - entry[1]
            val dg = g - entry[2]
            val db = b - entry[3]
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = entry
            }
        }
        return best[0].toByte()
    }

    /** Returns the [r, g, b] triple of a palette entry by its packed index. */
    fun paletteEntryOf(index: Byte): IntArray {
        val unsigned = index.toInt() and 0xFF
        return BY_INDEX[unsigned] ?: PALETTE[0]
    }
}
