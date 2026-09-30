package nomathexpectation.chatexchange.convert

/**
 * Typed model of an OneBot 11 message segment (`{"type": ..., "data": {...}}`),
 * as forwarded by NMEBoot via `OneBot11.DefaultJson.encodeToString(sourceSegments)`.
 *
 * All `data` fields are kept as strings regardless of their JSON kind: the OneBot 11
 * standard mandates string values, but implementations occasionally emit numbers.
 */
sealed interface OneBotSegment {
    data class Text(val text: String) : OneBotSegment

    /** Classic QQ face, identified by its numeric id (see [FaceTable]). */
    data class Face(val id: String) : OneBotSegment

    /** `flash = true` marks a flash photo (闪照). */
    data class Image(val file: String?, val url: String?, val flash: Boolean) : OneBotSegment

    data class Record(val file: String?, val url: String?) : OneBotSegment

    data class Video(val file: String?, val url: String?) : OneBotSegment

    /** `qq` is a plain number, or `"all"` for @-everyone. */
    data class At(val qq: String) : OneBotSegment

    data class RockPaperScissors(val value: Int?) : OneBotSegment

    data class Dice(val value: Int?) : OneBotSegment

    /** Poke segment; [qq] is the target when the implementation provides one. */
    data class Poke(val qq: String?, val pokeType: String?, val id: String?, val name: String?) : OneBotSegment

    data class Share(val url: String?, val title: String?, val content: String?) : OneBotSegment

    data class Contact(val contactType: String?, val id: String?) : OneBotSegment

    data class Location(val latitude: String?, val longitude: String?, val title: String?, val content: String?) : OneBotSegment

    /** Reply/quote reference; only carries the quoted message id (go-cqhttp style). */
    data class Reply(val id: String?) : OneBotSegment

    /** Merged-forward reference; content is NOT included and needs `get_forward_msg`. */
    data class Forward(val id: String?) : OneBotSegment

    /** Forward node; the custom form embeds a nested segment array in [content]. */
    data class ForwardNode(
        val id: String?,
        val userId: String?,
        val nickname: String?,
        val content: List<OneBotSegment>?,
    ) : OneBotSegment

    data class Xml(val data: String?) : OneBotSegment

    /** JSON card; QQ chat-record cards (`com.tencent.multimsg`) arrive in this form. */
    data class JsonCard(val data: String?) : OneBotSegment

    /** Non-standard or unmapped segment type; [data] keeps the raw JSON for preview. */
    data class Unknown(val type: String, val data: String?) : OneBotSegment
}
