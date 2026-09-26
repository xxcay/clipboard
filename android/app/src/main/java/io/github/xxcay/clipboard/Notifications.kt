package io.github.xxcay.clipboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ITEMS = "items"
    const val STATUS_ID = 1
    private const val ORANGE = 0xFFFF6A1F.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Фоновая связь", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Постоянное уведомление, пока приложение ждёт записи с других устройств"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ITEMS, "Новые записи", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Текст, ссылки и файлы с ПК и ноутбука"
                setSound(null, null)
            }
        )
    }

    private fun mainIntent(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun sendClipboardIntent(context: Context) = PendingIntent.getActivity(
        context, 1, Intent(context, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun status(context: Context, state: HubState): Notification {
        val text = when (state) {
            HubState.Online -> "На связи с роутером"
            HubState.Connecting -> "Подключение…"
            HubState.AuthFailed -> "Неверный токен — откройте настройки"
            HubState.NotConfigured -> "Не настроено"
            HubState.Offline -> "Нет связи с роутером"
        }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ORANGE)
            .setContentTitle("Общий буфер")
            .setContentText(text)
            .setContentIntent(mainIntent(context))
            .addAction(R.drawable.ic_upload, "Отправить буфер", sendClipboardIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun canNotify(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun itemId(item: ClipItem) = 1000 + (item.id.hashCode() and 0xFFFFFF)

    /** "New from the PC" with one-tap actions. [file] is the downloaded copy for files. */
    fun item(context: Context, item: ClipItem, file: File?) {
        if (!canNotify(context)) return
        val id = itemId(item)
        val b = NotificationCompat.Builder(context, CHANNEL_ITEMS)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(ORANGE)
            .setContentTitle("От ${item.from}")
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(item.ts)

        when {
            item.isFile -> {
                val meta = item.file!!
                b.setContentText("${meta.name} · ${Format.size(meta.size)}")
                if (file != null) {
                    if (item.isImage) {
                        decodePreview(file)?.let { bmp ->
                            b.setLargeIcon(bmp)
                            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
                        }
                    }
                    b.addAction(R.drawable.ic_open, "Открыть", activity(context, id + 1, Intents.openFile(context, file, meta.mime)))
                    b.addAction(R.drawable.ic_share, "Поделиться", activity(context, id + 2, Intents.shareFile(context, file, meta.mime)))
                    b.addAction(R.drawable.ic_save, "Сохранить", ActionReceiver.save(context, item, id))
                } else {
                    b.setContentText("${meta.name} · не удалось загрузить, откройте приложение")
                }
            }
            item.isUrl -> {
                val url = item.text.orEmpty()
                b.setContentText(url)
                b.setContentIntent(activity(context, id + 1, Intents.openUrl(url)))
                b.addAction(R.drawable.ic_open, "Открыть", activity(context, id + 2, Intents.openUrl(url)))
                b.addAction(R.drawable.ic_copy, "Копировать", ActionReceiver.copy(context, url, id))
            }
            else -> {
                val text = item.text.orEmpty()
                b.setContentText(text)
                b.setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
                b.addAction(R.drawable.ic_copy, "Копировать", ActionReceiver.copy(context, text, id))
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun decodePreview(file: File): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
