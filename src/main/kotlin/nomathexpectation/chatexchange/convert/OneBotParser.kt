package nomathexpectation.chatexchange.convert

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Result of inspecting an inbound `MessageEvent.content` string. */
sealed interface ParsedContent {
    /** The old plain-text format, optionally carrying `[[CICode,...]]` markers. */
    data class Legacy(val text: String) : ParsedContent

    /** A JSON-encoded OneBot 11 message segment array. */
    data class OneBot(val segments: List<OneBotSegment>) : ParsedContent
}

/**
 * Detects and parses the OneBot 11 wire format produced by NMEBoot
 * (`OneBot11.DefaultJson.encodeToString(sourceSegments)`). Anything that does not
 * strictly look like a `[{type, data}, ...]` array falls back to [ParsedContent.Legacy],
 * so non-OneBot data sources keep working through the CICode text path.
 */
object OneBotParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(content: String): ParsedContent {
        val trimmed = content.trim()
        if (!trimmed.startsWith("[")) {
            return ParsedContent.Legacy(content)
        }

        val array = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonArray
            ?: return ParsedContent.Legacy(content)
        if (array.isEmpty()) {
            return ParsedContent.Legacy(content)
        }

        val segments = mutableListOf<OneBotSegment>()
        for (element in array) {
            val segment = segmentOf(element) ?: return ParsedContent.Legacy(content)
            segments.add(segment)
        }
        return ParsedContent.OneBot(segments)
    }

    private fun segmentOf(element: JsonElement): OneBotSegment? {
        if (element is JsonArray) {
            return OneBotSegment.Unknown("array", element.toString())
        }
        val obj = element as? JsonObject ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val data = obj["data"] as? JsonObject
        return when (type) {
            "text" -> OneBotSegment.Text(str(data, "text") ?: "")
            "face" -> OneBotSegment.Face(str(data, "id") ?: "")
            "image" -> OneBotSegment.Image(
                file = str(data, "file"),
                url = str(data, "url"),
                flash = str(data, "type") == "flash",
            )
            "record" -> OneBotSegment.Record(str(data, "file"), str(data, "url"))
            "video" -> OneBotSegment.Video(str(data, "file"), str(data, "url"))
            "at" -> OneBotSegment.At(str(data, "qq") ?: "")
            "rps" -> OneBotSegment.RockPaperScissors(str(data, "value")?.toIntOrNull())
            "dice" -> OneBotSegment.Dice(str(data, "value")?.toIntOrNull())
            "poke" -> OneBotSegment.Poke(
                qq = str(data, "qq", "target_qq", "target"),
                pokeType = str(data, "type"),
                id = str(data, "id"),
                name = str(data, "name"),
            )
            "share" -> OneBotSegment.Share(str(data, "url"), str(data, "title"), str(data, "content"))
            "contact" -> OneBotSegment.Contact(str(data, "type"), str(data, "id"))
            "location" -> OneBotSegment.Location(
                str(data, "lat"),
                str(data, "lon"),
                str(data, "title"),
                str(data, "content"),
            )
            "reply" -> OneBotSegment.Reply(str(data, "id"))
            "forward" -> OneBotSegment.Forward(str(data, "id"))
            "node" -> nodeOf(data)
            "xml" -> OneBotSegment.Xml(str(data, "data"))
            "json" -> OneBotSegment.JsonCard(str(data, "data"))
            else -> OneBotSegment.Unknown(type, data?.toString())
        }
    }

    private fun nodeOf(data: JsonObject?): OneBotSegment.ForwardNode {
        return OneBotSegment.ForwardNode(
            id = str(data, "id"),
            userId = str(data, "user_id", "uin"),
            nickname = str(data, "nickname", "name"),
            content = contentOf(data?.get("content")),
        )
    }

    private fun contentOf(element: JsonElement?): List<OneBotSegment>? {
        return when (element) {
            null -> null
            is JsonArray -> element.map { segmentOf(it) ?: OneBotSegment.Unknown("unknown", it.toString()) }
            is JsonPrimitive -> if (element is JsonNull) null else listOf(OneBotSegment.Text(element.content))
            is JsonObject -> listOf(segmentOf(element) ?: OneBotSegment.Unknown("unknown", element.toString()))
        }
    }
}

/** First non-null string value among [keys] of [obj]; numbers/booleans are coerced to strings. */
internal fun str(obj: JsonObject?, vararg keys: String): String? {
    for (key in keys) {
        val value = obj?.get(key)
        if (value is JsonPrimitive && value !is JsonNull) {
            return value.content
        }
    }
    return null
}

/** Reads a top-level string field of a raw JSON object (used for card payloads). */
fun jsonStr(obj: JsonObject?, key: String): String? = str(obj, key)
