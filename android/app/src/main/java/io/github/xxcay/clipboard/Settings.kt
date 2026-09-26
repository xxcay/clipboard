package io.github.xxcay.clipboard

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bumped on every save, so screens can react. */
    val version = MutableStateFlow(0)

    var serverUrl: String
        get() = prefs.getString("serverUrl", "http://clip.lan:${ServerAddress.DEFAULT_PORT}")!!
        set(v) = prefs.edit { putString("serverUrl", v) }

    var token: String
        get() = prefs.getString("token", "")!!
        set(v) = prefs.edit { putString("token", v) }

    var deviceName: String
        get() = prefs.getString("deviceName", "Телефон")!!
        set(v) = prefs.edit { putString("deviceName", v) }

    /** Keep a connection in the background (foreground service). */
    var backgroundSync: Boolean
        get() = prefs.getBoolean("backgroundSync", true)
        set(v) = prefs.edit { putBoolean("backgroundSync", v) }

    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit { putBoolean("notifications", v) }

    /** Save files from other devices to Downloads/Общий буфер. */
    var autoSaveFiles: Boolean
        get() = prefs.getBoolean("autoSaveFiles", false)
        set(v) = prefs.edit { putBoolean("autoSaveFiles", v) }

    /** Put incoming text and links into the phone clipboard right away. */
    var autoCopyText: Boolean
        get() = prefs.getBoolean("autoCopyText", false)
        set(v) = prefs.edit { putBoolean("autoCopyText", v) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(v) = prefs.edit { putBoolean("askedNotifications", v) }

    val baseUrl: HttpUrl? get() = ServerAddress.parse(serverUrl)?.first

    val isConfigured: Boolean get() = token.isNotBlank() && baseUrl != null

    fun isMine(item: ClipItem) = item.from == deviceName.trim()

    fun changed() {
        version.value++
    }
}
