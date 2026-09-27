package io.github.xxcay.clipboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: Int) : UpdateState
    data class Downloading(val version: Int, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from the "android-latest" GitHub release: version.json holds the
 * build number (= versionCode), SharedClipboard.apk the app itself.
 */
class Updater(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val currentVersion: Int by lazy {
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()).toInt()
        }.getOrDefault(0)
    }

    val currentName: String by lazy {
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    /** Quiet check at most every few hours (when the app opens). */
    fun checkIfDue() {
        val last = prefs.getLong("lastCheck", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        check(quiet = true)
    }

    fun check(quiet: Boolean = false) {
        if (state.value is UpdateState.Checking || state.value is UpdateState.Downloading) return
        scope.launch {
            if (!quiet) state.value = UpdateState.Checking
            try {
                val latest = latestVersion()
                prefs.edit { putLong("lastCheck", System.currentTimeMillis()) }
                state.value = if (latest > currentVersion) UpdateState.Available(latest) else if (quiet) UpdateState.Idle else UpdateState.UpToDate
            } catch (e: Exception) {
                state.value = if (quiet) UpdateState.Idle else UpdateState.Failed("Нет связи с GitHub. Есть интернет?")
            }
        }
    }

    /** Downloads the new APK and opens the system installer. */
    fun install(context: Context) {
        val version = (state.value as? UpdateState.Available)?.version ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            // One-time permission: "Install unknown apps" for this app.
            android.widget.Toast.makeText(
                context, "Разрешите установку из этого источника и нажмите «Обновить» ещё раз", android.widget.Toast.LENGTH_LONG,
            ).show()
            context.startActivity(
                Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        scope.launch {
            try {
                state.value = UpdateState.Downloading(version, 0f)
                val apk = download { p -> state.value = UpdateState.Downloading(version, p) }
                state.value = UpdateState.Available(version)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                state.value = UpdateState.Failed("Не удалось скачать обновление")
            }
        }
    }

    private suspend fun latestVersion(): Int = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$BASE/version.json").header("Cache-Control", "no-cache").build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            JSONObject(it.body!!.string()).getInt("version")
        }
    }

    private suspend fun download(progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "SharedClipboard.apk")
        http.newCall(Request.Builder().url("$BASE/SharedClipboard.apk").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        }
        file
    }

    companion object {
        private const val BASE = "https://github.com/xxcay/clipboard/releases/download/android-latest"
        private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    }
}
