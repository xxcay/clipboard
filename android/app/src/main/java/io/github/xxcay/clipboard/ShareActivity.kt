package io.github.xxcay.clipboard

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.xxcay.clipboard.ui.ClipTheme
import io.github.xxcay.clipboard.ui.GradientButton
import io.github.xxcay.clipboard.ui.Logo
import io.github.xxcay.clipboard.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private sealed interface ShareState {
    data class Sending(val name: String, val progress: Float, val index: Int, val total: Int) : ShareState
    data class Done(val message: String) : ShareState
    data class Failed(val message: String) : ShareState
    data object NotConfigured : ShareState
}

/** "Share → Общий буфер" from any app: a small sheet that sends and closes itself. */
class ShareActivity : ComponentActivity() {
    private val state = MutableStateFlow<ShareState>(ShareState.Sending("", 0f, 0, 1))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.WHITE, AndroidColor.WHITE),
        )
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) {
            finish()
            return
        }
        setContent {
            ClipTheme {
                val s by state.collectAsStateWithLifecycle()
                ShareSheet(s, onClose = ::finish, onSettings = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                })
            }
        }
        if (savedInstanceState == null) lifecycleScope.launch { send(uris, text) }
    }

    private suspend fun send(uris: List<Uri>, text: String?) {
        if (!app.settings.isConfigured) {
            state.value = ShareState.NotConfigured
            return
        }
        try {
            if (uris.isEmpty()) {
                state.value = ShareState.Sending(if (isLink(text!!)) "Ссылка" else "Текст", 0.5f, 0, 1)
                app.api.sendText(text)
                state.value = ShareState.Done(if (isLink(text)) "Ссылка уже на ПК и ноутбуке" else "Текст уже на ПК и ноутбуке")
            } else {
                var last = ""
                for ((i, uri) in uris.withIndex()) {
                    val name = Api.displayName(contentResolver, uri)
                    last = name
                    state.value = ShareState.Sending(name, 0f, i, uris.size)
                    val item = app.api.upload(contentResolver, uri) { p ->
                        state.value = ShareState.Sending(name, p, i, uris.size)
                    }
                    app.cache.adopt(item, contentResolver, uri)
                }
                state.value = ShareState.Done(
                    if (uris.size == 1) last else Format.plural(uris.size, "файл отправлен", "файла отправлено", "файлов отправлено")
                )
            }
            delay(1300)
            finish()
        } catch (e: ClipException) {
            state.value = ShareState.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            state.value = ShareState.Failed("Не удалось отправить")
        }
    }

    private fun isLink(text: String) = text.trim().let { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains(' ') }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val list = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
        if (list.isNotEmpty()) return list
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}

@Composable
private fun ShareSheet(state: ShareState, onClose: () -> Unit, onSettings: () -> Unit) {
    val finished = state !is ShareState.Sending
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (finished) onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Color(0xFFE8DFD8)))
                Spacer(Modifier.height(24.dp))
                when (state) {
                    is ShareState.Sending -> {
                        Logo(64.dp)
                        Title("Отправляю на ПК и ноутбук")
                        Subtitle(state.name)
                        Spacer(Modifier.height(18.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            color = Palette.Orange,
                            trackColor = Palette.Soft,
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        )
                        if (state.total > 1) Subtitle("${state.index + 1} из ${state.total}")
                    }
                    is ShareState.Done -> {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.Gradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(38.dp))
                        }
                        Title("Отправлено!")
                        Subtitle(state.message)
                    }
                    is ShareState.Failed -> {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.DangerSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(38.dp))
                        }
                        Title("Не получилось")
                        Subtitle(state.message)
                        Spacer(Modifier.height(20.dp))
                        GradientButton("Закрыть", onClick = onClose, modifier = Modifier.fillMaxWidth())
                    }
                    ShareState.NotConfigured -> {
                        Logo(64.dp)
                        Title("Сначала настройте")
                        Subtitle("Укажите адрес роутера и токен в приложении")
                        Spacer(Modifier.height(20.dp))
                        GradientButton("Открыть настройки", onClick = onSettings, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp))
}

@Composable
private fun Subtitle(text: String) {
    Text(
        text,
        color = Palette.Muted,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}
