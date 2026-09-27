package io.github.xxcay.clipboard

import android.content.Context
import android.util.Log
import io.github.xxcay.clipboard.widget.ClipboardWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Live connection to the hub on the router (WebSocket). Runs while someone
 * holds it: the open app and/or the background service.
 */
class Hub(
    private val context: Context,
    private val settings: Settings,
    private val api: Api,
    private val cache: FileCache,
    http: OkHttpClient,
) {
    private enum class Outcome { Opened, Failed, Unauthorized }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsClient = http.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    val state = MutableStateFlow(if (settings.isConfigured) HubState.Connecting else HubState.NotConfigured)

    /** Newest first. */
    val items = MutableStateFlow<List<ClipItem>>(emptyList())
    val devices = MutableStateFlow<List<String>>(emptyList())

    /** New items as they arrive (not the snapshot sent on connect). */
    val added = MutableSharedFlow<ClipItem>(extraBufferCapacity = 64)

    private val holders = mutableSetOf<String>()
    private var loop: Job? = null
    private var prefetch: Job? = null
    private var widgetJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun retain(tag: String) {
        holders += tag
        ensureRunning()
    }

    @Synchronized
    fun release(tag: String) {
        holders -= tag
        if (holders.isEmpty()) stopLoop()
    }

    /** Settings changed: reconnect with the new address/token/name. */
    @Synchronized
    fun restart() {
        stopLoop()
        items.value = emptyList()
        state.value = if (settings.isConfigured) HubState.Connecting else HubState.NotConfigured
        ensureRunning()
    }

    /** Skip the back-off wait (network came back, user pulled to refresh…). */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    /**
     * The app came to the screen: make sure the list matches the router.
     * In the background Android may cut the network without closing the
     * socket, so deletions made meanwhile could be missed.
     */
    fun refresh() {
        if (state.value != HubState.Online) {
            reconnectNow()
            return
        }
        scope.launch {
            try {
                val list = api.list().reversed()
                items.value = list
                cache.sync(list)
                itemsChanged()
            } catch (e: Exception) {
                Log.w(TAG, "refresh", e)
                reconnectNow()
            }
        }
    }

    private fun ensureRunning() {
        if (loop?.isActive == true || holders.isEmpty()) return
        if (!settings.isConfigured) {
            state.value = HubState.NotConfigured
            return
        }
        loop = scope.launch { run() }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    private suspend fun run() {
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            state.value = HubState.Connecting
            when (connectOnce()) {
                Outcome.Unauthorized -> {
                    state.value = HubState.AuthFailed
                    waitOrWake(30_000)
                }
                Outcome.Opened -> {
                    backoff = 1_000L
                    state.value = HubState.Offline
                    waitOrWake(1_000)
                }
                Outcome.Failed -> {
                    state.value = HubState.Offline
                    waitOrWake(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun waitOrWake(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private suspend fun connectOnce(): Outcome = suspendCancellableCoroutine { cont ->
        val base = settings.baseUrl
        if (base == null) {
            cont.resume(Outcome.Failed)
            return@suspendCancellableCoroutine
        }
        val url = base.newBuilder()
            .addPathSegment("ws")
            .addQueryParameter("device", settings.deviceName.trim())
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${settings.token}").build()
        var opened = false
        val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (cont.isActive) runCatching { handle(text) }.onFailure { Log.w(TAG, "bad message", it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) cont.resume(if (opened) Outcome.Opened else Outcome.Failed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!cont.isActive) return
                cont.resume(
                    when {
                        response?.code == 401 -> Outcome.Unauthorized
                        opened -> Outcome.Opened
                        else -> Outcome.Failed
                    }
                )
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun handle(text: String) {
        val o = JSONObject(text)
        when (o.optString("type")) {
            "hello" -> {
                val list = ClipItem.listFromJson(o.optJSONArray("items")).reversed()
                o.optJSONObject("limits")?.let {
                    api.limits = Limits(it.optLong("maxTextBytes", 1L shl 20), it.optLong("maxFileBytes", 20L shl 20))
                }
                devices.value = stringList(o)
                items.value = list
                state.value = HubState.Online
                cache.sync(list)
                prefetchFiles()
                itemsChanged()
            }
            "clip" -> {
                val item = ClipItem.fromJson(o.optJSONObject("item")) ?: return
                if (items.value.any { it.id == item.id }) return
                items.value = listOf(item) + items.value
                added.tryEmit(item)
                if (!settings.isMine(item)) prefetchFiles()
                itemsChanged()
            }
            "delete" -> {
                val id = o.optString("id")
                items.value = items.value.filterNot { it.id == id }
                cache.remove(id)
                itemsChanged()
            }
            "devices" -> devices.value = stringList(o)
        }
    }

    private fun stringList(o: JSONObject): List<String> {
        val a = o.optJSONArray("devices") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    /** Files are small and the network is local: fetch them in advance, so opening is instant. */
    private fun prefetchFiles() {
        if (prefetch?.isActive == true) return
        prefetch = scope.launch {
            for (item in items.value) {
                if (item.isFile && !cache.isReady(item) && !settings.isMine(item)) {
                    runCatching { cache.ensure(item) }.onFailure { Log.w(TAG, "prefetch ${item.id}", it) }
                }
            }
        }
    }

    private fun itemsChanged() {
        widgetJob?.cancel()
        widgetJob = scope.launch {
            delay(400)
            ClipboardWidget.publish(context, items.value)
        }
    }

    companion object {
        private const val TAG = "Hub"
    }
}
