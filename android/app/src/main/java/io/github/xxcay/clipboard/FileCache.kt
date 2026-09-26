package io.github.xxcay.clipboard

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Local copies of the files on the hub: cache/shared/<item id>/<file name>.
 * The UI observes [ready] and [progress].
 */
class FileCache(private val context: Context, private val api: Api) {
    private val root = File(context.cacheDir, "shared")
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Ids of items whose files are on the phone. */
    val ready = MutableStateFlow<Set<String>>(emptySet())

    /** Download progress (0..1) by item id. */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun fileFor(item: ClipItem) = File(File(root, item.id), safeName(item.file!!.name))

    fun isReady(item: ClipItem): Boolean {
        if (!item.isFile) return false
        val f = fileFor(item)
        return f.exists() && f.length() == item.file!!.size
    }

    suspend fun ensure(item: ClipItem): File {
        if (isReady(item)) return fileFor(item).also { markReady(item.id) }
        val lock = locks.getOrPut(item.id) { Mutex() }
        return lock.withLock {
            val file = fileFor(item)
            if (!isReady(item)) {
                try {
                    api.download(item, file) { p -> progress.update { it + (item.id to p) } }
                } finally {
                    progress.update { it - item.id }
                }
            }
            markReady(item.id)
            file
        }
    }

    /** Puts a file we just uploaded into the cache, so it is not downloaded back. */
    suspend fun adopt(item: ClipItem, resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val dest = fileFor(item)
            dest.parentFile?.mkdirs()
            resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (isReady(item)) markReady(item.id)
        }
    }

    private fun markReady(id: String) = ready.update { it + id }

    fun remove(id: String) {
        File(root, id).deleteRecursively()
        ready.update { it - id }
    }

    /** Drops files of items that are no longer on the hub. */
    fun sync(items: List<ClipItem>) {
        val keep = items.map { it.id }.toSet()
        root.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
        ready.value = items.filter { isReady(it) }.map { it.id }.toSet()
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Copies a cached file to Downloads/Общий буфер (shows up in Gallery and My Files). */
    suspend fun saveToDownloads(item: ClipItem): String = withContext(Dispatchers.IO) {
        val file = ensure(item)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.file!!.name)
            put(MediaStore.Downloads.MIME_TYPE, item.file.mime.substringBefore(';').trim())
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ClipException("Не удалось сохранить файл")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw ClipException("Не удалось сохранить файл")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is ClipException) e else ClipException("Не удалось сохранить файл", e)
        }
        "Загрузки/$SAVE_FOLDER"
    }

    companion object {
        const val SAVE_FOLDER = "Общий буфер"

        fun safeName(name: String): String {
            val clean = name.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }
                .joinToString("").trim(' ', '.')
            return clean.ifEmpty { "file" }
        }
    }
}
