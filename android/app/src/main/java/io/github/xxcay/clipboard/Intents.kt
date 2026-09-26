package io.github.xxcay.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.File

/** Small helpers for opening, sharing and copying things. */
object Intents {
    fun copyText(context: Context, text: String, toast: Boolean = true) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Общий буфер", text))
        // Android 13+ shows its own "Copied" confirmation.
        if (toast && Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun openUrl(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openFile(context: Context, file: File, mime: String): Intent {
        val uri = context.app.cache.uriFor(file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, cleanMime(mime))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .also { it.clipData = ClipData.newRawUri(file.name, uri) }
    }

    fun shareFile(context: Context, file: File, mime: String): Intent {
        val uri = context.app.cache.uriFor(file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(cleanMime(mime))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(file.name, uri) }
        return Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareText(text: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun cleanMime(mime: String) = mime.substringBefore(';').trim().ifEmpty { "application/octet-stream" }

    /** Opens [intent], or tells the user there is no app for it. */
    fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Нет приложения, чтобы открыть это", Toast.LENGTH_SHORT).show()
        }
    }
}
