package com.goldorb

import android.app.Application
import android.content.Context
import com.goldorb.data.Notifier
import com.goldorb.data.OrbRepository
import com.goldorb.data.SettingsRepository
import com.goldorb.data.TradingViewAuth
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GoldOrbApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
    }
}

class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    val settings = SettingsRepository(context)
    val notifier = Notifier(context)
    val auth = TradingViewAuth(settings, http)
    val repository = OrbRepository(context, settings, auth, notifier, http)
}

val Context.container: AppContainer get() = (applicationContext as GoldOrbApp).container
