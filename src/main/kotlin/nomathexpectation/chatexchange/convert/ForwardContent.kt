package nomathexpectation.chatexchange.convert

import kotlinx.serialization.json.JsonObject

/** One record ("nickname: content" line) inside a merged-forward message. */
class ForwardRecord(
    val userId: String?,
    val nickname: String?,
    val content: List<OneBotSegment>?,
)

/**
 * Normalized merged-forward content. Records are only available when they were embedded
 * in the wire data (`node` segments); id-only `forward` segments and multimsg JSON cards
 * cannot be expanded without a `get_forward_msg` call on the OneBot side, leaving
 * [records] empty and [expandable] false (a [summary] may still be present for preview).
 */
class ForwardContent(
    val records: List<ForwardRecord>,
    val summary: String?,
) {
    val expandable: Boolean get() = records.any { it.content != null }

    companion object {
        fun fromForwardId(id: String?): ForwardContent = ForwardContent(emptyList(), id)

        fun fromNodes(nodes: List<OneBotSegment.ForwardNode>): ForwardContent =
            ForwardContent(nodes.map { ForwardRecord(it.userId, it.nickname, it.content) }, null)

        /**
         * Builds forward info from a JSON card segment payload
         * (`app == "com.tencent.multimsg"`); returns null for any other card.
         * The nested records are NOT embedded in the JSON (they live behind the resid),
         * so only the summary (e.g. "查看3条转发消息") is extracted.
         */
        fun fromJsonCard(obj: JsonObject?): ForwardContent? {
            if (jsonStr(obj, "app") != "com.tencent.multimsg") {
                return null
            }
            val detail = (obj?.get("meta") as? JsonObject)?.get("detail") as? JsonObject
            val summary = jsonStr(detail, "summary") ?: jsonStr(obj, "prompt")
            return ForwardContent(emptyList(), summary)
        }

        /**
         * Best-effort title of a non-multimsg JSON card (mini-program shares etc.):
         * the `prompt` field (with its leading "[来源]" tag stripped), else `desc`.
         */
        fun cardTitle(obj: JsonObject?): String? {
            val prompt = jsonStr(obj, "prompt")
                ?.replace(Regex("^\\[[^\\]]*\\]\\s*"), "")
                ?.takeIf { it.isNotBlank() }
            if (prompt != null) {
                return prompt
            }
            return jsonStr(obj, "desc")?.takeIf { it.isNotBlank() }
        }

        /**
         * Best-effort jump URL of a mini-program JSON card: the first object under
         * `meta` (detail_1/detail/notification/...), preferring `qqdocurl` (a full
         * https link) over `url` (often scheme-less, e.g. `m.q.qq.com/a/s/...`).
         */
        fun cardJumpUrl(obj: JsonObject?): String? {
            val meta = obj?.get("meta") as? JsonObject ?: return null
            val detail = meta.values.filterIsInstance<JsonObject>().firstOrNull() ?: return null
            jsonStr(detail, "qqdocurl")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.let { return it }
            val url = jsonStr(detail, "url")?.takeIf { it.isNotBlank() } ?: return null
            return if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"
        }
    }
}
