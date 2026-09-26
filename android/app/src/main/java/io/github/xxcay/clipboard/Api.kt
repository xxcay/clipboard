package io.github.xxcay.clipboard

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/** HTTP side of the hub: send text, move files, delete. Blocking calls run on IO. */
class Api(private val settings: Settings, val http: OkHttpClient) {
    @Volatile
    var limits = Limits()

    private fun request(path: String): Request.Builder {
        val base = settings.baseUrl ?: throw ClipException("Не настроен адрес роутера")
        val url = base.resolve(path) ?: throw ClipException("Неправильный адрес роутера")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.token}")
            .header("X-Device", URLEncoder.encode(settings.deviceName.trim(), "UTF-8"))
    }

    private fun <T> call(request: Request, block: (Response) -> T): T {
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ClipException("Роутер недоступен. Телефон в домашнем Wi-Fi?", e)
        }
        resp.use {
            if (!it.isSuccessful) throw errorFor(it)
            return block(it)
        }
    }

    private fun errorFor(r: Response): ClipException {
        val server = runCatching { JSONObject(r.body?.string().orEmpty()).optString("error") }.getOrNull()
        return ClipException(
            when (r.code) {
                401 -> "Неверный токен"
                413 -> "Слишком большой файл (максимум ${Format.size(limits.maxFileBytes)})"
                507 -> "На роутере закончилось место"
                404 -> "Запись уже удалена"
                else -> "Ошибка сервера: ${server?.ifEmpty { null } ?: r.code}"
            }
        )
    }

    suspend fun sendText(text: String): ClipItem = withContext(Dispatchers.IO) {
        if (text.isBlank()) throw ClipException("Пустой текст")
        if (text.toByteArray().size > limits.maxTextBytes) throw ClipException("Текст слишком длинный")
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())
        call(request("api/clip").post(body).build()) { ClipItem.fromJson(JSONObject(it.body!!.string()))!! }
    }

    suspend fun list(): List<ClipItem> = withContext(Dispatchers.IO) {
        call(request("api/items").get().build()) { ClipItem.listFromJson(JSONArray(it.body!!.string())) }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        try {
            call(request("api/items/$id").delete().build()) { }
        } catch (e: ClipException) {
            if (e.message != "Запись уже удалена") throw e
        }
    }

    /** Uploads a content:// or file:// Uri as is (no recompression). */
    suspend fun upload(
        resolver: ContentResolver,
        uri: Uri,
        nameOverride: String? = null,
        progress: (Float) -> Unit = {},
    ): ClipItem = withContext(Dispatchers.IO) {
        val name = nameOverride ?: displayName(resolver, uri)
        // First pass: size and SHA-256 (the hub verifies the upload with it).
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw ClipException("Не удалось открыть $name")
        input.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                size += n
                if (size > limits.maxFileBytes) throw ClipException("$name: больше ${Format.size(limits.maxFileBytes)}")
            }
        }
        if (size == 0L) throw ClipException("$name: пустой файл")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val mime = resolver.getType(uri)
        val body = object : RequestBody() {
            override fun contentType() = mime?.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                (resolver.openInputStream(uri) ?: throw IOException("gone")).use { s ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        progress(done.toFloat() / size)
                    }
                }
            }
        }
        val url = request("api/files").build().url.newBuilder().addQueryParameter("name", name).build()
        val req = request("api/files").url(url).header("X-Sha256", sha).put(body).build()
        call(req) { ClipItem.fromJson(JSONObject(it.body!!.string()))!! }
    }

    /** Downloads a file item to [dest], checking its SHA-256. */
    suspend fun download(item: ClipItem, dest: File, progress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val meta = item.file ?: throw IllegalArgumentException("not a file")
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        call(request("api/files/${item.id}").get().build()) { resp ->
            val digest = MessageDigest.getInstance("SHA-256")
            resp.body!!.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        done += n
                        if (meta.size > 0) progress(done.toFloat() / meta.size)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(meta.sha256, ignoreCase = true)) {
                part.delete()
                throw ClipException("${meta.name}: файл повреждён при передаче")
            }
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    /** Returns null when address and token work, or a message for the user. */
    suspend fun check(): String? = withContext(Dispatchers.IO) {
        try {
            call(request("api/items").get().build()) { }
            null
        } catch (e: ClipException) {
            e.message
        }
    }

    companion object {
        fun displayName(resolver: ContentResolver, uri: Uri): String {
            val fromQuery = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            return fromQuery?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        }
    }
}
