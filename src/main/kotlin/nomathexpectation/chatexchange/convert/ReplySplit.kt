package nomathexpectation.chatexchange.convert

/**
 * Splits a leading reply (quote) segment off an OneBot segment list.
 *
 * go-cqhttp-style implementations report a reply as
 * `[reply(id), ...quoted original segments..., at(original sender), text(" ...new content...")]`,
 * where the `at` + leading-space text run is auto-inserted by the QQ client. There is no
 * explicit delimiter, so the boundary is detected as the first `at` segment directly
 * followed by a text segment starting with a space, requiring a non-empty quoted part.
 * When no boundary is found the split degrades to "quoted unknown, everything is body".
 */
class ReplySplit(
    val reply: OneBotSegment.Reply?,
    val quoted: List<OneBotSegment>?,
    val body: List<OneBotSegment>,
)

fun splitReplyPrefix(segments: List<OneBotSegment>): ReplySplit {
    val first = segments.firstOrNull()
    if (first !is OneBotSegment.Reply) {
        return ReplySplit(null, null, segments)
    }

    val rest = segments.drop(1)
    if (rest.isEmpty()) {
        return ReplySplit(first, null, rest)
    }

    var boundary = -1
    for (i in rest.indices) {
        if (i == 0) {
            continue
        }
        val at = rest[i]
        val after = rest.getOrNull(i + 1)
        if (at is OneBotSegment.At && after is OneBotSegment.Text && after.text.startsWith(" ")) {
            boundary = i
            break
        }
    }

    if (boundary < 0) {
        return ReplySplit(first, null, rest)
    }

    val quoted = rest.subList(0, boundary).toList()
    val bodyTail = rest.drop(boundary + 1).toMutableList()
    val firstTail = bodyTail.firstOrNull()
    if (firstTail is OneBotSegment.Text) {
        val stripped = firstTail.text.removePrefix(" ")
        if (stripped.isEmpty()) {
            bodyTail.removeAt(0)
        } else {
            bodyTail[0] = firstTail.copy(text = stripped)
        }
    }

    return ReplySplit(first, quoted.ifEmpty { null }, bodyTail)
}
