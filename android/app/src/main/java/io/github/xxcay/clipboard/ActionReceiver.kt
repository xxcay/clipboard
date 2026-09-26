package io.github.xxcay.clipboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Notification buttons that do not open anything: "Copy" and "Save". */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        when (intent.action) {
            ACTION_COPY -> {
                Intents.copyText(context, intent.getStringExtra(EXTRA_TEXT).orEmpty())
                Notifications.cancel(context, notificationId)
            }
            ACTION_SAVE -> {
                val item = ClipItem.fromJson(runCatching { JSONObject(intent.getStringExtra(EXTRA_ITEM).orEmpty()) }.getOrNull())
                    ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.Main).launch {
                    val msg = try {
                        "Сохранено в ${context.app.cache.saveToDownloads(item)}"
                    } catch (e: ClipException) {
                        e.message.orEmpty()
                    } catch (e: Exception) {
                        "Не удалось сохранить"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    Notifications.cancel(context, notificationId)
                    pending.finish()
                }
            }
        }
    }

    companion object {
        private const val ACTION_COPY = "io.github.xxcay.clipboard.COPY"
        private const val ACTION_SAVE = "io.github.xxcay.clipboard.SAVE"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_NOTIFICATION = "notification"

        fun copy(context: Context, text: String, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, ActionReceiver::class.java).setAction(ACTION_COPY)
                .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun save(context: Context, item: ClipItem, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, notificationId + 7,
            Intent(context, ActionReceiver::class.java).setAction(ACTION_SAVE)
                .putExtra(EXTRA_ITEM, item.toJson().toString()).putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
