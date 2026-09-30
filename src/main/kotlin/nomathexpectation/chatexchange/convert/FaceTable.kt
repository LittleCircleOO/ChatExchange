package nomathexpectation.chatexchange.convert

/**
 * Best-effort emoji fallback table for classic QQ faces (ids 0-88, the pre-emoji-era set
 * shared by virtually every OneBot implementation). Newer "big face" ids have no stable
 * mapping and render as nothing.
 */
object FaceTable {
    private val TABLE: Map<String, String> = mapOf(
        "0" to "🙂", "1" to "😒", "2" to "😍", "3" to "😳", "4" to "😏", "5" to "😢",
        "6" to "😊", "7" to "🤐", "8" to "😴", "9" to "😭", "10" to "😓", "11" to "😠",
        "12" to "😛", "13" to "😁", "14" to "😲", "15" to "😞", "16" to "😎", "17" to "😰",
        "18" to "😫", "19" to "🤮", "20" to "🤭", "21" to "🥰", "22" to "🙄", "23" to "😤",
        "24" to "🤤", "25" to "😪", "26" to "😱", "27" to "😅", "28" to "😄", "29" to "🫡",
        "30" to "💪", "31" to "🤬", "32" to "❓", "33" to "🤫", "34" to "😵", "35" to "😖",
        "36" to "😩", "37" to "💀", "38" to "🔨", "39" to "👋", "40" to "😅", "41" to "👃",
        "42" to "👏", "43" to "🫣", "44" to "😈", "45" to "😾", "46" to "😾", "47" to "🥱",
        "48" to "😑", "49" to "🥺", "50" to "🥹", "51" to "😼", "52" to "😘", "53" to "😨",
        "54" to "😿", "55" to "🔪", "56" to "🍉", "57" to "🍺", "58" to "🏀", "59" to "🏓",
        "60" to "☕", "61" to "🍚", "62" to "🐷", "63" to "🌹", "64" to "🥀", "65" to "❤️",
        "66" to "💔", "67" to "🎂", "68" to "⚡", "69" to "💣", "70" to "🗡️", "71" to "⚽",
        "72" to "🐞", "73" to "💩", "74" to "🌙", "75" to "☀️", "76" to "🎁", "77" to "🤗",
        "78" to "👍", "79" to "👎", "80" to "🤝", "81" to "✌️", "82" to "🙏", "83" to "👉",
        "84" to "✊", "85" to "🤨", "86" to "🤟", "87" to "🙅", "88" to "👌",
    )

    /** Emoji for a classic face id, or null when unmapped (render nothing). */
    fun emoji(id: String): String? = TABLE[id]

    fun rockPaperScissors(value: Int?): String = when (value) {
        0 -> "✊"
        1 -> "✌️"
        2 -> "✋"
        else -> "✊"
    }

    fun dice(@Suppress("UNUSED_PARAMETER") value: Int?): String = "🎲"
}
