@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package io.github.xxcay.clipboard.ui

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xxcay.clipboard.ClipException
import io.github.xxcay.clipboard.ClipItem
import io.github.xxcay.clipboard.Format
import io.github.xxcay.clipboard.HubState
import io.github.xxcay.clipboard.Intents
import io.github.xxcay.clipboard.app
import kotlinx.coroutines.launch
import java.io.File

private enum class Filter(val label: String) { All("Все"), Files("Файлы"), Links("Ссылки"), Text("Текст") }

@Composable
fun MainScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val items by app.hub.items.collectAsStateWithLifecycle()
    val state by app.hub.state.collectAsStateWithLifecycle()
    val ready by app.cache.ready.collectAsStateWithLifecycle()
    val progress by app.cache.progress.collectAsStateWithLifecycle()
    val upload by app.sender.upload.collectAsStateWithLifecycle()
    val update by app.updater.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var sheetItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var errorSnack by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun toast(text: String, error: Boolean = false) {
        errorSnack = error
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    LaunchedEffect(Unit) {
        app.sender.messages.collect { (text, error) -> toast(text, error) }
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) app.sender.sendUris(uris)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) app.sender.sendUris(uris)
    }

    // ---- item actions ----
    fun fileOf(item: ClipItem, then: (File) -> Unit) {
        scope.launch {
            try {
                then(app.cache.ensure(item))
            } catch (e: ClipException) {
                toast(e.message.orEmpty(), true)
            } catch (e: Exception) {
                toast("Не удалось загрузить файл", true)
            }
        }
    }

    fun copy(item: ClipItem) {
        Intents.copyText(context, item.text.orEmpty(), toast = false)
        toast("Скопировано")
    }

    fun primary(item: ClipItem) = when {
        item.isUrl -> Intents.start(context, Intents.openUrl(item.text.orEmpty()))
        item.isFile -> fileOf(item) { Intents.start(context, Intents.openFile(context, it, item.file!!.mime)) }
        else -> copy(item)
    }

    fun share(item: ClipItem) {
        if (item.isFile) fileOf(item) { Intents.start(context, Intents.shareFile(context, it, item.file!!.mime)) }
        else Intents.start(context, Intents.shareText(item.text.orEmpty()))
    }

    fun save(item: ClipItem) = scope.launch {
        try {
            toast("Сохранено в ${app.cache.saveToDownloads(item)}")
        } catch (e: ClipException) {
            toast(e.message.orEmpty(), true)
        }
    }

    fun delete(item: ClipItem) = scope.launch {
        try {
            app.api.delete(item.id)
        } catch (e: ClipException) {
            toast(e.message.orEmpty(), true)
        }
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        app.sender.sendClip(clip)
    }

    val filtered = items.filter {
        when (filter) {
            Filter.All -> true
            Filter.Files -> it.isFile
            Filter.Links -> it.isUrl
            Filter.Text -> it.isText
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item { Header(items, onSettings) }
            item { ConnectionBanner(state, onRetry = { app.hub.reconnectNow() }, onSettings = onSettings) }
            item {
                UpdateCard(update, onInstall = { app.updater.install(context) }, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp))
            }
            item {
                SendCard(
                    onPaste = ::pasteFromClipboard,
                    onPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onFiles = { pickFiles.launch("*/*") },
                )
            }
            item { Composer(onSend = { app.sender.sendText(it) }) }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filter.entries.forEach { f -> Chip(f.label, filter == f) { filter = f } }
                }
            }
            if (filtered.isEmpty()) {
                item { EmptyState(filter, items.isEmpty()) }
            } else {
                items(filtered, key = { it.id }) { item ->
                    val file = if (item.id in ready) app.cache.fileFor(item) else null
                    Box(Modifier.animateItem().padding(horizontal = 20.dp, vertical = 5.dp)) {
                        SwipeToDelete(onDelete = {
                            try {
                                app.api.delete(item.id)
                                true
                            } catch (e: ClipException) {
                                toast(e.message.orEmpty(), true)
                                false
                            }
                        }) {
                            ItemCard(
                                item = item,
                                mine = app.settings.isMine(item),
                                file = file,
                                progress = progress[item.id],
                                onClick = { primary(item) },
                                onLongClick = { sheetItemId = item.id },
                                onAction = { primary(item) },
                            )
                        }
                    }
                }
            }
        }

        // Upload progress + snackbar at the bottom.
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = upload != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                upload?.let { UploadCard(it.name, it.progress, it.index, it.total) }
            }
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    containerColor = Palette.Ink,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (errorSnack) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF8A8A), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(data.visuals.message)
                    }
                }
            }
        }
    }

    val sheetItem = items.firstOrNull { it.id == sheetItemId }
    if (sheetItem != null) {
        ModalBottomSheet(onDismissRequest = { sheetItemId = null }, containerColor = Color.White) {
            ItemSheet(
                item = sheetItem,
                mine = app.settings.isMine(sheetItem),
                file = if (sheetItem.id in ready) app.cache.fileFor(sheetItem) else null,
                onAction = { action ->
                    sheetItemId = null
                    when (action) {
                        SheetAction.Open -> primary(sheetItem)
                        SheetAction.Copy -> copy(sheetItem)
                        SheetAction.Share -> share(sheetItem)
                        SheetAction.Save -> save(sheetItem)
                        SheetAction.Delete -> delete(sheetItem)
                    }
                },
            )
        }
    }
}

@Composable
private fun Header(items: List<ClipItem>, onSettings: () -> Unit) {
    val files = items.count { it.isFile }
    val subtitle = when {
        items.isEmpty() -> "Скопируйте здесь — вставьте на ПК"
        files > 0 -> "${Format.plural(items.size, "запись", "записи", "записей")} · ${Format.plural(files, "файл", "файла", "файлов")}"
        else -> Format.plural(items.size, "запись", "записи", "записей")
    }
    Row(
        Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(50.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Общий буфер", style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        IconButton(
            onClick = onSettings,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White),
            modifier = Modifier.size(46.dp).shadow(2.dp, CircleShape),
        ) {
            Icon(Icons.Rounded.Settings, "Настройки", tint = Palette.Ink)
        }
    }
}

@Composable
private fun ConnectionBanner(state: HubState, onRetry: () -> Unit, onSettings: () -> Unit) {
    // Shown only when something is wrong; the "reconnecting" blink is ignored.
    var problem by remember { mutableStateOf<HubState?>(null) }
    LaunchedEffect(state) {
        when (state) {
            HubState.Online -> problem = null
            HubState.Offline, HubState.AuthFailed, HubState.NotConfigured -> problem = state
            HubState.Connecting -> {}
        }
    }
    AnimatedVisibility(visible = problem != null) {
        val p = problem ?: HubState.Offline
        val settingsProblem = p == HubState.AuthFailed || p == HubState.NotConfigured
        Surface(
            color = Palette.DangerSoft,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp).fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (settingsProblem) Icons.Rounded.ErrorOutline else Icons.Rounded.WifiOff, null, tint = Palette.Danger, modifier = Modifier.size(20.dp))
                Text(
                    when (p) {
                        HubState.AuthFailed -> "Неверный токен"
                        HubState.NotConfigured -> "Не настроено подключение"
                        else -> "Нет связи с роутером"
                    },
                    color = Palette.Danger,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                TextButton(onClick = if (settingsProblem) onSettings else onRetry) {
                    Text(if (settingsProblem) "Настроить" else "Повторить", color = Palette.Danger, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SendCard(onPaste: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = Palette.Orange, spotColor = Palette.Orange)
            .clip(RoundedCornerShape(30.dp))
            .background(Palette.Gradient)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.42f, center = Offset(size.width * 0.98f, size.height * 0.02f))
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.25f, center = Offset(size.width * 0.05f, size.height * 1.05f))
            }
            .padding(20.dp),
    ) {
        Column {
            Text("Отправить на ПК и ноутбук", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(
                "Скопируйте что угодно — и одно нажатие",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = onPaste,
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ContentPaste, null, tint = Palette.Orange, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Вставить из буфера", color = Palette.OrangeDeep, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Фото и видео", Icons.Rounded.PhotoLibrary, onPhotos, Modifier.weight(1f))
                GlassButton("Файл", Icons.Rounded.AttachFile, onFiles, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GlassButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
        modifier = modifier.height(50.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Написать текст или ссылку…", color = Palette.Muted) },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Palette.Orange,
                ),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Palette.Orange,
                    contentColor = Color.White,
                    disabledContainerColor = Palette.Soft,
                    disabledContentColor = Color(0xFFFFB38A),
                ),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Отправить", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: ClipItem,
    mine: Boolean,
    file: File?,
    progress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = Color.White,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemTile(item, file)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Palette.Ink,
                )
                Text(
                    Format.meta(item, mine),
                    fontSize = 12.5.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = Palette.Orange,
                        trackColor = Palette.Soft,
                        modifier = Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).clip(CircleShape),
                    )
                }
            }
            val icon = when {
                item.isText -> Icons.Rounded.ContentCopy
                item.isUrl -> Icons.AutoMirrored.Rounded.OpenInNew
                file == null -> Icons.Rounded.FileDownload
                else -> Icons.AutoMirrored.Rounded.OpenInNew
            }
            Surface(onClick = onAction, shape = CircleShape, color = Palette.Soft, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Palette.Orange, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(filter: Filter, nothingAtAll: Boolean) {
    val (title, hint) = when {
        nothingAtAll -> "Здесь пока пусто" to "Скопируйте что-нибудь на ПК или нажмите «Вставить из буфера» здесь"
        filter == Filter.Files -> "Файлов нет" to "Отправьте фото или файл кнопками выше"
        filter == Filter.Links -> "Ссылок нет" to "Скопируйте ссылку и нажмите «Вставить из буфера»"
        else -> "Текста нет" to "Напишите текст в поле выше"
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Palette.Soft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ContentPaste, null, tint = Palette.Orange, modifier = Modifier.size(38.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            hint,
            color = Palette.Muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun UploadCard(name: String, progress: Float, index: Int, total: Int) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Palette.Ink,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                progress = { progress },
                color = Palette.OrangeLight,
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeWidth = 3.dp,
                modifier = Modifier.size(30.dp),
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text("Отправка: $name", color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = if (total > 1) "${index + 1} из $total · " else ""
                Text("$count${(progress * 100).toInt()}%", color = Color.White.copy(alpha = 0.65f), fontSize = 12.5.sp)
            }
        }
    }
}

private enum class SheetAction { Open, Copy, Share, Save, Delete }

@Composable
private fun ItemSheet(item: ClipItem, mine: Boolean, file: File?, onAction: (SheetAction) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ItemTile(item, file, 56.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(Format.meta(item, mine), color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            item.isFile -> {
                SheetRow(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть") { onAction(SheetAction.Open) }
                SheetRow(Icons.Rounded.Share, "Поделиться") { onAction(SheetAction.Share) }
                SheetRow(Icons.Rounded.FileDownload, "Сохранить в Загрузки") { onAction(SheetAction.Save) }
            }
            item.isUrl -> {
                SheetRow(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть ссылку") { onAction(SheetAction.Open) }
                SheetRow(Icons.Rounded.ContentCopy, "Копировать") { onAction(SheetAction.Copy) }
                SheetRow(Icons.Rounded.Share, "Поделиться") { onAction(SheetAction.Share) }
            }
            else -> {
                SheetRow(Icons.Rounded.ContentCopy, "Копировать") { onAction(SheetAction.Copy) }
                SheetRow(Icons.Rounded.Share, "Поделиться") { onAction(SheetAction.Share) }
            }
        }
        SheetRow(Icons.Rounded.DeleteOutline, "Удалить у всех", danger = true) { onAction(SheetAction.Delete) }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) Palette.Danger else Palette.Orange
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (danger) Palette.DangerSoft else Palette.Soft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
            Text(
                text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (danger) Palette.Danger else Palette.Ink,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}
