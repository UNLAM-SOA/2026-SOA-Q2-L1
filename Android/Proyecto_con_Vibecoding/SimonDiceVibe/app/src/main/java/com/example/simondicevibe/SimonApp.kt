package com.example.simondicevibe

import android.app.Application
import android.content.Context
import com.example.simondicevibe.data.history.HistoryRepository
import com.example.simondicevibe.data.mqtt.MqttGameClient
import com.example.simondicevibe.domain.SessionStore

/** Tiny manual DI container: one MQTT client / repository / session for the whole process. */
class AppContainer(context: Context) {
    val mqtt = MqttGameClient()
    val history = HistoryRepository(context.applicationContext)
    val session = SessionStore()
}

class SimonApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
