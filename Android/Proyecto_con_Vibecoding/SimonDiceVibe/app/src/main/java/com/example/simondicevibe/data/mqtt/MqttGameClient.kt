package com.example.simondicevibe.data.mqtt

import android.util.Log
import com.example.simondicevibe.domain.EspMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

enum class BrokerStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Paho wrapper with the same connection semantics as the original app:
 *  - retained `app/conexion = online` on connect, retained LWT `offline`
 *  - subscribes to `esp32/#` (QoS 1), publishes `app/start` (QoS 1, not retained)
 *  - retries the very first connect every 5 s; Paho's automatic reconnect afterwards.
 * State is exposed as Flows so the UI layer never touches Paho types.
 */
class MqttGameClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _brokerStatus = MutableStateFlow(BrokerStatus.CONNECTING)
    val brokerStatus: StateFlow<BrokerStatus> = _brokerStatus.asStateFlow()

    private val _espOnline = MutableStateFlow(false)
    val espOnline: StateFlow<Boolean> = _espOnline.asStateFlow()

    private val _messages = MutableSharedFlow<EspMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<EspMessage> = _messages.asSharedFlow()

    private var client: MqttAsyncClient? = null
    private var started = false

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            _brokerStatus.value = BrokerStatus.CONNECTED
            try {
                client?.subscribe(
                    arrayOf(MqttContract.TOPIC_ESP32_ALL, MqttContract.TOPIC_LIVE),
                    intArrayOf(1, 0),
                )
                client?.publish(MqttContract.TOPIC_APP_STATUS, MqttContract.ONLINE.toByteArray(), 1, true)
            } catch (e: MqttException) {
                Log.e(TAG, "subscribe/publish after connect failed", e)
            }
        }

        override fun connectionLost(cause: Throwable?) {
            _espOnline.value = false
            _brokerStatus.value = BrokerStatus.CONNECTING // Paho reconnects automatically
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            val text = message.toString()
            if (topic == MqttContract.TOPIC_ESP32_STATUS) {
                _espOnline.value = text == MqttContract.ONLINE
                return
            }
            if (message.isRetained) return // stale state from a previous game
            EspMessageParser.parse(topic.substringAfterLast('/'), text)?.let { _messages.tryEmit(it) }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    @Synchronized
    fun connect() {
        if (started) return
        started = true
        client = MqttAsyncClient(MqttContract.BROKER_URI, MqttClient.generateClientId(), MemoryPersistence())
            .also { it.setCallback(callback) }
        attemptConnect()
    }

    fun publishStart() {
        try {
            client?.publish(MqttContract.TOPIC_APP_START, MqttContract.START_PAYLOAD.toByteArray(), 1, false)
        } catch (e: MqttException) {
            Log.e(TAG, "could not publish start", e)
        }
    }

    private fun attemptConnect() {
        val c = client ?: return
        _brokerStatus.value = BrokerStatus.CONNECTING
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            setWill(MqttContract.TOPIC_APP_STATUS, MqttContract.OFFLINE.toByteArray(), 1, true)
        }
        try {
            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = scheduleRetry()
            })
        } catch (e: MqttException) {
            Log.e(TAG, "connect threw", e)
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        _brokerStatus.value = BrokerStatus.DISCONNECTED
        scope.launch {
            delay(RETRY_MS)
            attemptConnect()
        }
    }

    private companion object {
        const val TAG = "MqttGameClient"
        const val RETRY_MS = 5_000L
    }
}
