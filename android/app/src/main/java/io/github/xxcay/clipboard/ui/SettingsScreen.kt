@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.xxcay.clipboard.ui

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xxcay.clipboard.R
import io.github.xxcay.clipboard.SendTileService
import io.github.xxcay.clipboard.SetupLink
import io.github.xxcay.clipboard.ServerAddress
import io.github.xxcay.clipboard.SyncService
import io.github.xxcay.clipboard.app
import io.github.xxcay.clipboard.widget.ClipboardWidgetReceiver
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(firstRun: Boolean, prefill: SetupLink?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val s = app.settings
    val scope = rememberCoroutineScope()

    var address by rememberSaveable(prefill) { mutableStateOf(prefill?.server ?: s.serverUrl) }
    var token by rememberSaveable(prefill) { mutableStateOf(prefill?.token ?: s.token) }
    var device by rememberSaveable(prefill) { mutableStateOf(prefill?.name ?: s.deviceName) }
    var background by rememberSaveable { mutableStateOf(s.backgroundSync) }
    var notifications by rememberSaveable { mutableStateOf(s.notifications) }
    var autoSave by rememberSaveable { mutableStateOf(s.autoSaveFiles) }
    var autoCopy by rememberSaveable { mutableStateOf(s.autoCopyText) }
    var check by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(context)) }

    fun applyTo(target: io.github.xxcay.clipboard.Settings) {
        target.serverUrl = ServerAddress.parse(address)?.first?.let { ServerAddress.display(it) } ?: address.trim()
        target.token = token.trim()
        target.deviceName = device.trim().ifEmpty { "Телефон" }
    }

    fun save() {
        if (ServerAddress.parse(address) == null) {
            check = false to "Неправильный адрес роутера"
            return
        }
        if (token.isBlank()) {
            check = false to "Введите токен"
            return
        }
        applyTo(s)
        s.backgroundSync = background
        s.notifications = notifications
        s.autoSaveFiles = autoSave
        s.autoCopyText = autoCopy
        s.changed()
        app.hub.restart()
        SyncService.apply(context)
        onSaved()
    }

    fun runCheck() {
        // Test the values typed on screen without saving them yet.
        val saved = Triple(s.serverUrl, s.token, s.deviceName)
        applyTo(s)
        checking = true
        check = null
        scope.launch {
            val error = app.api.check()
            s.serverUrl = saved.first
            s.token = saved.second
            s.deviceName = saved.third
            checking = false
            check = if (error == null) true to "Всё работает: роутер отвечает" else false to error
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.padding(top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Palette.Ink) }
                Spacer(Modifier.width(4.dp))
            }
            Text(if (firstRun) "Добро пожаловать" else "Настройки", style = MaterialTheme.typography.headlineSmall)
        }

        if (firstRun) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Palette.Gradient)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Общий буфер", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Укажите адрес роутера и токен — их выдал установщик на роутере",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        SectionTitle("ПОДКЛЮЧЕНИЕ")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Field(
                    label = "Адрес роутера",
                    value = address,
                    onChange = { v ->
                        // A pasted quick link fills in the token too.
                        val parsed = ServerAddress.parse(v)
                        if (parsed?.second != null) {
                            token = parsed.second!!
                            address = ServerAddress.display(parsed.first)
                        } else {
                            address = v
                        }
                    },
                    icon = Icons.Rounded.Router,
                    hint = "clip.lan или 192.168.8.1:8765. Можно вставить ссылку с #token=…",
                    keyboard = KeyboardType.Uri,
                )
                Field(
                    label = "Токен",
                    value = token,
                    onChange = { token = it },
                    icon = Icons.Rounded.VpnKey,
                    hint = "На роутере: uci get clipd.main.token",
                    mono = true,
                )
                Field(
                    label = "Имя этого телефона",
                    value = device,
                    onChange = { device = it },
                    icon = Icons.Rounded.Smartphone,
                    hint = "Так его увидят ПК и ноутбук",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = ::runCheck,
                        enabled = !checking,
                        shape = RoundedCornerShape(14.dp),
                        color = Palette.Soft,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (checking) {
                                CircularProgressIndicator(color = Palette.Orange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Rounded.Wifi, null, tint = Palette.Orange, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Проверить связь", color = Palette.OrangeDeep, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                check?.let { (ok, text) ->
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
                            tint = if (ok) Palette.Ok else Palette.Danger, modifier = Modifier.size(18.dp),
                        )
                        Text(text, color = if (ok) Palette.Ok else Palette.Danger, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("ТЕЛЕФОН")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SwitchRow("Работать в фоне", "Получать записи, даже когда приложение закрыто", background) { background = it }
                HorizontalDivider(color = Palette.Line)
                SwitchRow("Уведомления", "Показывать новое с ПК и ноутбука с кнопками «Копировать» и «Открыть»", notifications) { notifications = it }
                HorizontalDivider(color = Palette.Line)
                SwitchRow("Сохранять файлы в Загрузки", "Фото и файлы сразу появятся в Галерее и «Моих файлах»", autoSave) { autoSave = it }
                HorizontalDivider(color = Palette.Line)
                SwitchRow("Сразу копировать текст", "Пришедший текст и ссылки сразу в буфере телефона", autoCopy) { autoCopy = it }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("БЫСТРЫЙ ДОСТУП")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                ActionRow(Icons.Rounded.Widgets, "Виджет на главный экран", "Последние записи и кнопка «Отправить буфер»") {
                    pinWidget(context)
                }
                ActionRow(Icons.Rounded.TouchApp, "Плитка в шторке", "«Отправить буфер» в быстрых настройках") {
                    addTile(context)
                }
                ActionRow(
                    Icons.Rounded.BatteryChargingFull,
                    "Не ограничивать в фоне",
                    "Чтобы Samsung не обрывал связь с роутером",
                    trailing = if (batteryOk) "Готово" else null,
                ) {
                    requestBattery(context)
                    batteryOk = isIgnoringBattery(context)
                }
                ActionRow(Icons.Rounded.Share, "Меню «Поделиться»", "В любом приложении: Поделиться → Общий буфер", onClick = null)
            }
        }

        Spacer(Modifier.height(28.dp))
        GradientButton("Сохранить", onClick = ::save, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    icon: ImageVector,
    hint: String,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = Palette.Orange) },
        supportingText = { Text(hint) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Orange,
            unfocusedBorderColor = Color(0xFFE8DFD8),
            focusedLabelColor = Palette.Orange,
            cursorColor = Palette.Orange,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

private fun pinWidget(context: Context) {
    val awm = AppWidgetManager.getInstance(context)
    if (awm.isRequestPinAppWidgetSupported) {
        awm.requestPinAppWidget(ComponentName(context, ClipboardWidgetReceiver::class.java), null, null)
    } else {
        Toast.makeText(context, "Зажмите пустое место на главном экране → Виджеты → Общий буфер", Toast.LENGTH_LONG).show()
    }
}

@SuppressLint("WrongConstant")
private fun addTile(context: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        val sbm = context.getSystemService(StatusBarManager::class.java)
        sbm?.requestAddTileService(
            ComponentName(context, SendTileService::class.java),
            context.getString(R.string.tile_label),
            Icon.createWithResource(context, R.drawable.ic_upload),
            context.mainExecutor,
        ) { }
    } else {
        Toast.makeText(context, "Потяните шторку → ✎ → перетащите «Отправить буфер»", Toast.LENGTH_LONG).show()
    }
}

private fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@SuppressLint("BatteryLife")
private fun requestBattery(context: Context) {
    if (isIgnoringBattery(context)) {
        Toast.makeText(context, "Уже разрешено", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
