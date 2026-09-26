package io.github.xxcay.clipboard

import android.content.ClipData
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Upload in progress, for the UI. */
data class Upload(val name: String, val progress: Float, val index: Int, val total: Int)

/**
 * Sends things from the app. Lives in the Application, so an upload
 * survives screen rotation and leaving the screen.
 */
class Sender(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val upload = MutableStateFlow<Upload?>(null)

    /** Short messages for a snackbar: text to error flag. */
    val messages = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)

    fun sendText(text: String) = scope.launch {
        try {
            app.api.sendText(text)
            messages.emit("Отправлено на другие устройства" to false)
        } catch (e: ClipException) {
            messages.emit(e.message.orEmpty() to true)
        }
    }

    fun sendUris(uris: List<Uri>) = scope.launch {
        var sent = 0
        var lastName = ""
        for ((i, uri) in uris.withIndex()) {
            val name = Api.displayName(app.contentResolver, uri)
            lastName = name
            upload.value = Upload(name, 0f, i, uris.size)
            try {
                val item = app.api.upload(app.contentResolver, uri) { p ->
                    upload.value = Upload(name, p, i, uris.size)
                }
                app.cache.adopt(item, app.contentResolver, uri)
                sent++
            } catch (e: ClipException) {
                messages.emit(e.message.orEmpty() to true)
            } catch (e: Exception) {
                messages.emit("$name: не удалось отправить" to true)
            }
        }
        upload.value = null
        when {
            sent == 1 -> messages.emit("Отправлено: $lastName" to false)
            sent > 1 -> messages.emit("Отправлено файлов: $sent" to false)
        }
    }

    /** Sends what is on the phone clipboard (text, a link or a copied picture). */
    fun sendClip(clip: ClipData?) {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        when {
            item == null -> scope.launch { messages.emit("Буфер обмена пуст" to true) }
            item.uri != null -> sendUris((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri })
            else -> {
                val text = item.coerceToText(app).toString()
                if (text.isBlank()) scope.launch { messages.emit("Буфер обмена пуст" to true) } else sendText(text)
            }
        }
    }
}
