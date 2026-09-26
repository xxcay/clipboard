package io.github.xxcay.clipboard

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Keeps the connection to the router while the app is closed, so text, links
 * and files from the PC arrive as notifications (and optionally straight into
 * the clipboard / Downloads).
 */
class SyncService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        // Must go foreground right away, even if we stop immediately after.
        ServiceCompat.startForeground(
            this, Notifications.STATUS_ID, Notifications.status(this, app.hub.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        app.hub.retain(TAG)
        lifecycleScope.launch {
            app.hub.state.collect { state ->
                runCatching {
                    NotificationManagerCompat.from(this@SyncService)
                        .notify(Notifications.STATUS_ID, Notifications.status(this@SyncService, state))
                }
            }
        }
        lifecycleScope.launch {
            app.hub.added.collect { item -> runCatching { onAdded(item) }.onFailure { Log.w(TAG, "item", it) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!app.settings.backgroundSync || !app.settings.isConfigured) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        app.hub.release(TAG)
        super.onDestroy()
    }

    private suspend fun onAdded(item: ClipItem) {
        val settings = app.settings
        if (settings.isMine(item)) return
        var file: java.io.File? = null
        if (item.isFile) {
            file = runCatching { app.cache.ensure(item) }.getOrNull()
            if (file != null && settings.autoSaveFiles) runCatching { app.cache.saveToDownloads(item) }
        } else if (settings.autoCopyText) {
            Intents.copyText(this, item.text.orEmpty(), toast = false)
        }
        if (settings.notifications && !App.inForeground) Notifications.item(this, item, file)
    }

    companion object {
        private const val TAG = "SyncService"

        fun start(context: Context) {
            val s = context.app.settings
            if (!s.backgroundSync || !s.isConfigured) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java)) }
                .onFailure { Log.w(TAG, "start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }

        /** Applies the "work in background" setting. */
        fun apply(context: Context) = if (context.app.settings.backgroundSync) start(context) else stop(context)
    }
}
