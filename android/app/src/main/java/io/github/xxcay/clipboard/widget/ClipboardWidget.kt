package io.github.xxcay.clipboard.widget

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.xxcay.clipboard.ClipItem
import io.github.xxcay.clipboard.ClipboardSendActivity
import io.github.xxcay.clipboard.Format
import io.github.xxcay.clipboard.Intents
import io.github.xxcay.clipboard.MainActivity
import io.github.xxcay.clipboard.R
import io.github.xxcay.clipboard.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private val Ink = ColorProvider(Color(0xFF1C1917))
private val Muted = ColorProvider(Color(0xFF8C837D))
private val Orange = ColorProvider(Color(0xFFFF6A1F))
private val White = ColorProvider(Color.White)
private val Red = ColorProvider(Color(0xFFE5484D))

/** Home screen widget: "Send clipboard" button and the latest items. */
class ClipboardWidget : GlanceAppWidget() {
    // The list lives in the widget's own state, so an update redraws it with fresh data.
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val me = context.app.settings.deviceName.trim()
        // A freshly added widget: start with what the app already knows.
        val hubItems = context.app.hub.items.value
        if (hubItems.isNotEmpty()) {
            runCatching { updateAppWidgetState(context, id) { prefs -> if (prefs[ITEMS_KEY] == null) prefs[ITEMS_KEY] = toJson(hubItems) } }
        }
        provideContent {
            val json = currentState<Preferences>()[ITEMS_KEY]
            Content(context, parse(json), me)
        }
    }

    @Composable
    private fun Content(context: Context, items: List<ClipItem>, me: String) {
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.widget_bg))
                .padding(14.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Logo and title open the app too.
                Row(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    GlanceModifier.size(34.dp).background(ImageProvider(R.drawable.widget_logo_bg)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_logo), null, GlanceModifier.size(19.dp))
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text("Общий буфер", style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(
                        if (items.isEmpty()) "ПК · ноутбук · телефон" else Format.plural(items.size, "запись", "записи", "записей"),
                        style = TextStyle(color = Muted, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
                }
                Box(
                    GlanceModifier
                        .size(34.dp)
                        .background(ImageProvider(R.drawable.widget_soft_bg))
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_open), "Открыть приложение", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(Orange))
                }
            }

            Spacer(GlanceModifier.height(10.dp))
            Row(
                GlanceModifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(ImageProvider(R.drawable.widget_button_bg))
                    .clickable(actionStartActivity(Intent(context, ClipboardSendActivity::class.java))),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(ImageProvider(R.drawable.ic_upload), null, GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text("Отправить буфер", style = TextStyle(color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            }

            Spacer(GlanceModifier.height(8.dp))
            if (items.isEmpty()) {
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text("Пока пусто", style = TextStyle(color = Muted, fontSize = 13.sp))
                }
            } else {
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(items, itemId = { it.id.hashCode().toLong() }) { item ->
                        ItemRow(context, item, item.from == me)
                    }
                }
            }
        }
    }

    @Composable
    private fun ItemRow(context: Context, item: ClipItem, mine: Boolean) {
        val action = when {
            item.isText -> actionRunCallback<CopyAction>(actionParametersOf(CopyAction.TextKey to item.text.orEmpty()))
            item.isUrl -> actionStartActivity(Intents.openUrl(item.text.orEmpty()))
            else -> actionStartActivity(Intent(context, MainActivity::class.java))
        }
        val icon = when {
            item.isUrl -> R.drawable.ic_link
            item.isText -> R.drawable.ic_text
            item.isImage -> R.drawable.ic_image
            else -> R.drawable.ic_file
        }
        Column(GlanceModifier.fillMaxWidth().padding(top = 5.dp)) {
            Row(
                GlanceModifier
                    .fillMaxWidth()
                    .background(ImageProvider(R.drawable.widget_row_bg))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(action),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    GlanceModifier.size(32.dp).background(ImageProvider(R.drawable.widget_soft_bg)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(icon), null, GlanceModifier.size(17.dp), colorFilter = ColorFilter.tint(Orange))
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(item.title, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Text(Format.meta(item, mine), style = TextStyle(color = Muted, fontSize = 11.sp), maxLines = 1)
                }
                Spacer(GlanceModifier.width(6.dp))
                Box(
                    GlanceModifier
                        .size(32.dp)
                        .background(ImageProvider(R.drawable.widget_delete_bg))
                        .clickable(actionRunCallback<DeleteAction>(actionParametersOf(DeleteAction.IdKey to item.id))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.ic_delete), "Удалить", GlanceModifier.size(17.dp), colorFilter = ColorFilter.tint(Red))
                }
            }
        }
    }

    companion object {
        private val ITEMS_KEY = stringPreferencesKey("items")
        private const val MAX = 6

        private fun toJson(items: List<ClipItem>): String {
            val array = JSONArray()
            items.take(MAX).forEach { array.put(it.toJson()) }
            return array.toString()
        }

        private fun parse(json: String?): List<ClipItem> =
            runCatching { ClipItem.listFromJson(JSONArray(json ?: "[]")) }.getOrDefault(emptyList())

        /** Puts the newest items into every widget's state and redraws them. */
        suspend fun publish(context: Context, items: List<ClipItem>) {
            val json = toJson(items)
            runCatching {
                val ids = GlanceAppWidgetManager(context).getGlanceIds(ClipboardWidget::class.java)
                for (id in ids) {
                    updateAppWidgetState(context, id) { prefs -> prefs[ITEMS_KEY] = json }
                    ClipboardWidget().update(context, id)
                }
            }
        }
    }
}

class ClipboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClipboardWidget()
}

/** Tap on a text item in the widget: copy it. */
class CopyAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val text = parameters[TextKey] ?: return
        withContext(Dispatchers.Main) { Intents.copyText(context, text) }
    }

    companion object {
        val TextKey = ActionParameters.Key<String>("text")
    }
}

/** Trash button in a widget row: delete the item everywhere. */
class DeleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[IdKey] ?: return
        val app = context.app
        try {
            app.api.delete(id)
            // Redraw right away; the hub (if connected) sends the same change.
            val current = app.hub.items.value.filterNot { it.id == id }
            ClipboardWidget.publish(context, current.ifEmpty { app.api.list().reversed() })
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, e.message ?: "Нет связи с роутером", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        val IdKey = ActionParameters.Key<String>("id")
    }
}
