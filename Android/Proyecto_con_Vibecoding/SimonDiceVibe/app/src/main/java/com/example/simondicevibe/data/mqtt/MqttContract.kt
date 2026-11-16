package com.example.simondicevibe.data.mqtt

/**
 * The MQTT contract shared with the ESP32 sketch and the Node-RED flow (Edge-Cloud).
 * Broker, prefix and payload formats are identical to the original student project.
 */
object MqttContract {
    const val BROKER_URI = "tcp://broker.hivemq.com:1883"
    const val DEVICE_ID = "juegito-l01"
    const val PREFIX = "juegito/$DEVICE_ID/"

    const val TOPIC_ESP32_ALL = PREFIX + "esp32/#"
    const val TOPIC_ESP32_STATUS = PREFIX + "esp32/conexion"
    const val TOPIC_APP_STATUS = PREFIX + "app/conexion"
    const val TOPIC_APP_START = PREFIX + "app/start"

    /** Optional extra telemetry, outside the prefix Node-RED stores (history stays clean). */
    const val TOPIC_LIVE = "juegito-live/$DEVICE_ID/esp32/paso"

    const val ONLINE = "online"
    const val OFFLINE = "offline"
    const val START_PAYLOAD = "start"
}
