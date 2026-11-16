package com.example.soaappandroid

import android.os.Handler
import android.os.Looper
import android.util.Log
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

object ConexionMqtt {

    private const val BROKER = "tcp://broker.hivemq.com:1883"
    private const val PREFIJO = "juegito/juegito-l01/"
    private const val TOPICS_ESP32 = PREFIJO + "esp32/#"
    private const val TOPIC_ESP32_CONEXION = PREFIJO + "esp32/conexion"
    private const val TOPIC_APP_CONEXION = PREFIJO + "app/conexion"
    private const val TOPIC_APP_START = PREFIJO + "app/start"
    private const val CONECTADA = "online"
    private const val DESCONECTADA = "offline"

    private const val ESPERA_REINTENTO_MS = 5000L

    private lateinit var cliente: MqttAsyncClient
    private var iniciada = false

    @Volatile
    var esp32Conectada = false
        private set

    var alCambiarEstado: ((Boolean) -> Unit)? = null
    var alRecibirMensaje: ((String, String) -> Unit)? = null

    fun conectar() {
        if (iniciada) return
        iniciada = true

        cliente = MqttAsyncClient(BROKER, MqttClient.generateClientId(), MemoryPersistence())
        cliente.setCallback(object : MqttCallbackExtended {

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                cliente.subscribe(TOPICS_ESP32, 1)
                cliente.publish(TOPIC_APP_CONEXION, CONECTADA.toByteArray(), 1, true)
            }

            override fun connectionLost(cause: Throwable?) {
                avisarEstado(false)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val texto = message?.toString() ?: ""
                if (topic == TOPIC_ESP32_CONEXION) {
                    avisarEstado(texto == CONECTADA)
                } else if (topic != null) {
                    alRecibirMensaje?.invoke(topic.substringAfterLast("/"), texto)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        intentarConectar()
    }

    fun publicarStart() {
        try {
            cliente.publish(TOPIC_APP_START, "start".toByteArray(), 1, false)
        } catch (e: MqttException) {
            Log.e("ConexionMqtt", "No se pudo publicar start", e)
        }
    }

    private fun intentarConectar() {
        val opciones = MqttConnectOptions()
        opciones.isAutomaticReconnect = true
        opciones.setWill(TOPIC_APP_CONEXION, DESCONECTADA.toByteArray(), 1, true)

        cliente.connect(opciones, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {}

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { intentarConectar() },
                    ESPERA_REINTENTO_MS
                )
            }
        })
    }

    private fun avisarEstado(conectada: Boolean) {
        esp32Conectada = conectada
        alCambiarEstado?.invoke(conectada)
    }
}
