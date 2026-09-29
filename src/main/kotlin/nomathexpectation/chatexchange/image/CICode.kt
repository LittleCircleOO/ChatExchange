package nomathexpectation.chatexchange.image

/**
 * Parsing of ChatImage-compatible `[[CICode,...]]` markers embedded in message content,
 * independent of the ChatImageCode library (which requires the stopped ChatImage mod at runtime).
 *
 * Only the subset needed for inbound rendering is handled: `url=` values pointing to
 * http(s) resources or inline `base64://` data. Anything else (e.g. legacy `file:///`)
 * is preserved as literal text.
 */
object CICode {
    private val CI_CODE_REGEX = Regex("""\[\[CICode,[^\[\]]*\]\]""")

    sealed interface ImageSource {
        data class Http(val url: String) : ImageSource
        data class Inline(val base64: String) : ImageSource
    }

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Image(val raw: String, val source: ImageSource?) : Segment
    }

    /** Splits message content into plain text segments and CICode image references. */
    fun split(content: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var cursor = 0

        for (match in CI_CODE_REGEX.findAll(content)) {
            if (match.range.first > cursor) {
                segments.add(Segment.Text(content.substring(cursor, match.range.first)))
            }

            segments.add(Segment.Image(match.value, parseSource(match.value)))
            cursor = match.range.last + 1
        }

        if (cursor < content.length) {
            segments.add(Segment.Text(content.substring(cursor)))
        }

        return segments
    }

    private fun parseSource(code: String): ImageSource? {
        val params = code
            .removeSurrounding("[[", "]]")
            .split(",")
            .asSequence()
            .filter { "=" in it }
            .map { it.split("=", limit = 2) }
            .associate { it[0].trim() to it[1].trim() }

        return when (val url = params["url"]) {
            null -> null
            else -> when {
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                    ImageSource.Http(url)
                url.startsWith("base64://", ignoreCase = true) ->
                    ImageSource.Inline(url.removePrefix("base64://"))
                else -> null
            }
        }
    }
}
