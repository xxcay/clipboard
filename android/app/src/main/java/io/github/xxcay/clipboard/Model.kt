package io.github.xxcay.clipboard

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A file stored on the hub, available at GET /api/files/{item id}. */
data class FileMeta(val name: String, val size: Long, val mime: String, val sha256: String)

/** One entry of the shared clipboard, as stored on the hub (clipd). */
data class ClipItem(
    val id: String,
    val kind: String,
    val from: String,
    val ts: Long,
    val text: String?,
    val file: FileMeta?,
) {
    val isFile get() = kind == KIND_FILE && file != null
    val isUrl get() = kind == KIND_URL
    val isText get() = !isFile && !isUrl

    val title: String
        get() = if (isFile) file!!.name else (text ?: "").trim().let { if (it.length > 400) it.take(400) + "…" else it }

    val isImage get() = isFile && file!!.mime.startsWith("image/")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("from", from); put("ts", ts)
        text?.let { put("text", it) }
        file?.let {
            put("file", JSONObject().apply {
                put("name", it.name); put("size", it.size); put("mime", it.mime); put("sha256", it.sha256)
            })
        }
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_URL = "url"
        const val KIND_FILE = "file"

        fun fromJson(o: JSONObject?): ClipItem? {
            if (o == null) return null
            val id = o.optString("id")
            if (id.isEmpty()) return null
            val f = o.optJSONObject("file")
            return ClipItem(
                id = id,
                kind = o.optString("kind", KIND_TEXT),
                from = o.optString("from"),
                ts = o.optLong("ts"),
                text = if (o.has("text")) o.optString("text") else null,
                file = f?.let {
                    FileMeta(it.optString("name", "file"), it.optLong("size"), it.optString("mime", "application/octet-stream"), it.optString("sha256"))
                },
            )
        }

        fun listFromJson(a: JSONArray?): List<ClipItem> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }
    }
}

data class Limits(
    val maxTextBytes: Long = 1L shl 20,
    val maxFileBytes: Long = 20L shl 20,
)

enum class HubState { NotConfigured, Connecting, Online, Offline, AuthFailed }

/** An error with a message that can be shown to the user as is. */
class ClipException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Format {
    private val ru = Locale("ru")

    fun size(bytes: Long): String = when {
        bytes >= 1 shl 20 -> String.format(ru, "%.1f МБ", bytes / 1048576.0)
        bytes >= 1 shl 10 -> String.format(ru, "%.0f КБ", bytes / 1024.0)
        else -> "$bytes Б"
    }

    fun time(ts: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "d MMM, HH:mm"
        return SimpleDateFormat(pattern, ru).format(Date(ts))
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        val word = when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
        return "$n $word"
    }

    fun meta(item: ClipItem, mine: Boolean): String {
        val parts = mutableListOf(if (mine) "Вы" else item.from, time(item.ts))
        if (item.isFile) parts += size(item.file!!.size)
        return parts.joinToString(" · ")
    }
}

object ServerAddress {
    const val DEFAULT_PORT = 8765

    /**
     * Accepts "clip.lan", "192.168.8.1:8765", "http://clip.lan:8765" or the
     * quick link printed by the router installer ("http://…:8765/#token=…").
     * A missing port means 8765: port 80 is the GL.iNet admin page.
     */
    fun parse(input: String?): Pair<HttpUrl, String?>? {
        var s = input?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val rest = s.substringAfter("://")
        val authority = rest.split('/', '?', '#').first()
        val hasPort = authority.lastIndexOf(':') > authority.lastIndexOf(']')
        val token = url.fragment?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == "token" && it[1].isNotEmpty() }
            ?.get(1)
        val base = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(if (hasPort) url.port else DEFAULT_PORT)
            .build()
        return base to token
    }

    fun display(url: HttpUrl): String = url.toString().trimEnd('/')
}
