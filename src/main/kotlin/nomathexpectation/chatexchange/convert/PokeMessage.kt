package nomathexpectation.chatexchange.convert

/**
 * Returns the poke segment when the message is a poke-only message — only poke segments
 * plus blank text — since pokes are action notices rather than chat content and use a
 * dedicated broadcast format. Returns null for anything else (mixed-content messages
 * keep inline rendering, where poke segments stay hidden).
 */
fun pokeMessageOf(segments: List<OneBotSegment>): OneBotSegment.Poke? {
    var poke: OneBotSegment.Poke? = null
    for (segment in segments) {
        when (segment) {
            is OneBotSegment.Poke -> if (poke == null) poke = segment
            is OneBotSegment.Text -> if (segment.text.isNotBlank()) return null
            else -> return null
        }
    }
    return poke
}
