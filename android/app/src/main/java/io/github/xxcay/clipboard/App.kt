package io.github.xxcay.clipboard

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var api: Api
        private set
    lateinit var cache: FileCache
        private set
    lateinit var hub: Hub
        private set
    lateinit var sender: Sender
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        api = Api(settings, http)
        cache = FileCache(this, api)
        hub = Hub(this, settings, api, cache, http)
        sender = Sender(this)
        updater = Updater(this)
        Notifications.createChannels(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> inForeground = true
                Lifecycle.Event.ON_STOP -> inForeground = false
                else -> {}
            }
        })

        // Wi-Fi came back (e.g. arrived home): reconnect right away.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = hub.reconnectNow()
            }
        )
    }

    companion object {
        @Volatile
        var inForeground = false
            private set
    }
}

val Context.app: App get() = applicationContext as App
