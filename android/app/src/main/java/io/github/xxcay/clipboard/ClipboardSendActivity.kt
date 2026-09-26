package io.github.xxcay.clipboard

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible screen behind "Send clipboard" (widget, quick settings tile,
 * notification button, app shortcut). Android lets apps read the clipboard
 * only while they have focus, so we take it, send it and close.
 */
class ClipboardSendActivity : ComponentActivity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.settings.isConfigured) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started || isFinishing) return
        started = true
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        lifecycleScope.launch {
            val message = try {
                when {
                    item == null -> "Буфер обмена пуст"
                    item.uri != null -> {
                        toast("Отправляю…")
                        val sent = app.api.upload(contentResolver, item.uri)
                        app.cache.adopt(sent, contentResolver, item.uri)
                        "Отправлено: ${sent.title}"
                    }
                    else -> {
                        val text = item.coerceToText(this@ClipboardSendActivity).toString()
                        if (text.isBlank()) "Буфер обмена пуст" else {
                            app.api.sendText(text)
                            "Отправлено на ПК и ноутбук"
                        }
                    }
                }
            } catch (e: ClipException) {
                e.message.orEmpty()
            } catch (e: Exception) {
                "Не удалось отправить"
            }
            toast(message)
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}
