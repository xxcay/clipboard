package io.github.xxcay.clipboard

import android.Manifest
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.xxcay.clipboard.ui.ClipTheme
import io.github.xxcay.clipboard.ui.MainScreen
import io.github.xxcay.clipboard.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent { ClipTheme { Root() } }
    }

    override fun onStart() {
        super.onStart()
        app.hub.retain(HOLDER)
        SyncService.start(this)
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
private fun Root() {
    val context = LocalContext.current
    val app = context.app
    var screen by rememberSaveable { mutableStateOf(if (app.settings.isConfigured) "main" else "settings") }

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
                onBack = { if (app.settings.isConfigured) screen = "main" },
                onSaved = { screen = "main" },
            )
        }
    }
}
