package nomathexpectation.chatexchange.image

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.awt.image.BufferedImage

/**
 * Renders images as colored text art (one styled block character per pixel),
 * following the classic chat-pixel-art approach: `█` for solid pixels, `▒` for
 * semi-transparent ones, and a bold+non-bold space pair (same total advance width)
 * for fully transparent ones. Consecutive same-color pixels are run-length merged
 * to keep the serialized component small.
 */
object AsciiArt {
    private const val SOLID_PIXEL = "█"
    private const val TRANSPARENT_PIXEL = "▒"
    private const val MID_ALPHA = 128

    class Result(val component: MutableComponent, val widthChars: Int)

    fun render(image: BufferedImage, maxWidth: Int, maxHeight: Int): Result {
        val scaled = image.scaleToFit(maxWidth, maxHeight).trimTransparent()
        val width = scaled.width
        val height = scaled.height
        val root = Component.literal("")

        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val pixel = scaled.getRGB(x, y)
                val alpha = pixel ushr 24

                if (alpha == 0) {
                    // Transparent pixel: bold space + non-bold space (matches block glyph advance).
                    root.append(
                        Component.literal(" ").withStyle(Style.EMPTY.withBold(true))
                            .append(Component.literal(" ").withStyle(Style.EMPTY.withBold(false)))
                    )
                    x++
                    continue
                }

                val rgb = pixel and 0xFFFFFF
                val glyph = if (alpha > MID_ALPHA) SOLID_PIXEL else TRANSPARENT_PIXEL

                // Run-length merge consecutive pixels with identical glyph and color.
                var run = 1
                while (x + run < width) {
                    val next = scaled.getRGB(x + run, y)
                    val nextAlpha = next ushr 24
                    if (nextAlpha == 0 || (next and 0xFFFFFF) != rgb ||
                        (if (nextAlpha > MID_ALPHA) SOLID_PIXEL else TRANSPARENT_PIXEL) != glyph
                    ) {
                        break
                    }
                    run++
                }

                root.append(
                    Component.literal(glyph.repeat(run))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)))
                )
                x += run
            }

            if (y < height - 1) {
                root.append(Component.literal("\n"))
            }
        }

        return Result(root, width)
    }
}

/**
 * Crops fully transparent rows/columns from the image edges (letterbox padding
 * from aspect-preserving scaling, cf. ChatImage's trimTransparency), so blank
 * lines/spaces never count against the configured size limits.
 */
private fun BufferedImage.trimTransparent(): BufferedImage {
    var top = 0
    var bottom = height - 1
    var left = 0
    var right = width - 1

    fun rowBlank(y: Int): Boolean {
        for (x in 0 until width) {
            if (getRGB(x, y) ushr 24 != 0) {
                return false
            }
        }
        return true
    }

    fun columnBlank(x: Int): Boolean {
        for (y in 0 until height) {
            if (getRGB(x, y) ushr 24 != 0) {
                return false
            }
        }
        return true
    }

    while (top <= bottom && rowBlank(top)) {
        top++
    }
    if (top > bottom) {
        return this // fully transparent
    }
    while (rowBlank(bottom)) {
        bottom--
    }
    while (columnBlank(left)) {
        left++
    }
    while (columnBlank(right)) {
        right--
    }

    if (left == 0 && top == 0 && right == width - 1 && bottom == height - 1) {
        return this
    }
    return getSubimage(left, top, right - left + 1, bottom - top + 1)
}
