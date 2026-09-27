package io.github.xxcay.clipboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.xxcay.clipboard.ui.ClipTheme
import io.github.xxcay.clipboard.ui.MainScreen
import io.github.xxcay.clipboard.ui.SettingsScreen

/** Values from a sharedclipboard://setup link; shown in settings, saved only by the user. */
data class SetupLink(val server: String?, val token: String?, val name: String?)

class MainActivity : ComponentActivity() {
    private val setup = mutableStateOf<SetupLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (savedInstanceState == null) setup.value = parseSetup(intent)
        setContent { ClipTheme { Root(setup.value, onSetupShown = { setup.value = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseSetup(intent)?.let { setup.value = it }
    }

    private fun parseSetup(intent: Intent?): SetupLink? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "sharedclipboard" || uri.host != "setup") return null
        return SetupLink(uri.getQueryParameter("server"), uri.getQueryParameter("token"), uri.getQueryParameter("name"))
    }

    override fun onStart() {
        super.onStart()
        app.hub.retain(HOLDER)
        app.hub.refresh()
        SyncService.start(this)
        app.updater.checkIfDue()
    }

    override fun onStop() {
        app.hub.release(HOLDER)
        super.onStop()
    }

    private companion object {
        const val HOLDER = "ui"
    }
}

@Composable
private fun Root(setup: SetupLink?, onSetupShown: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    var screen by rememberSaveable { mutableStateOf(if (app.settings.isConfigured) "main" else "settings") }
    var prefill by remember { mutableStateOf<SetupLink?>(null) }
    LaunchedEffect(setup) {
        if (setup != null) {
            prefill = setup
            screen = "settings"
            onSetupShown()
        }
    }

    // Ask once for notifications (Android 13+), after the app is set up.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(screen) {
        if (screen == "main" && Build.VERSION.SDK_INT >= 33 && !app.settings.askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            app.settings.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen == "settings" && app.settings.isConfigured) { screen = "main" }

    Crossfade(targetState = screen, label = "screen") { current ->
        when (current) {
            "main" -> MainScreen(onSettings = { screen = "settings" })
            else -> SettingsScreen(
                firstRun = !app.settings.isConfigured,
                prefill = prefill,
                onBack = {
                    prefill = null
                    if (app.settings.isConfigured) screen = "main"
                },
                onSaved = {
                    prefill = null
                    screen = "main"
                },
            )
        }
    }
}
