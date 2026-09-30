package nomathexpectation.chatexchange.convert

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * QQ-number to display-nickname map, stored in `config/chatexchange_nicknames.json`
 * and used to render `at` segments. Hot-reloaded whenever the file's modification
 * time changes; keys are matched verbatim, so anything non-numeric (like the bundled
 * `_readme` entry) is simply never looked up.
 */
object Nicknames {
    private val json = Json { ignoreUnknownKeys = true }

    private var file: Path? = null
    private var cached: Map<String, String> = emptyMap()
    private var stamp: FileTime? = null

    /** Creates the file with a `_readme` hint if missing and primes the cache. */
    fun prepare(configDir: Path) {
        val path = configDir.resolve("chatexchange_nicknames.json")
        file = path
        runCatching {
            if (Files.notExists(path)) {
                Files.writeString(
                    path,
                    """
                    {
                      "_readme": "QQ number -> display nickname map, used to render @ mentions and poke targets (falls back to the raw number when unmapped). Changes are hot-reloaded on file modification. The entries below are examples - replace them with your own.",
                      "123456": "张三",
                      "654321": "李四"
                    }
                    """.trimIndent() + System.lineSeparator(),
                )
            }
        }
        refresh(force = true)
    }

    fun get(qq: String): String? {
        refresh(force = false)
        return cached[qq]
    }

    private fun refresh(force: Boolean) {
        val path = file ?: return
        val mtime = runCatching { Files.getLastModifiedTime(path) }.getOrNull() ?: return
        if (!force && mtime == stamp) {
            return
        }
        stamp = mtime
        cached = runCatching {
            json.decodeFromString<Map<String, String>>(Files.readString(path))
        }.getOrDefault(cached)
    }
}
