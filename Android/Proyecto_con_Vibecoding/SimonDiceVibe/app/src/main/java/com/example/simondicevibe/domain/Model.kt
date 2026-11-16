package com.example.simondicevibe.domain

/** The four playable elements. [wire] is the exact string the ESP32 publishes. */
enum class Pad(val wire: String, val label: String) {
    ROJO("ROJO", "Rojo"),
    AZUL("AZUL", "Azul"),
    VERDE("VERDE", "Verde"),
    SENSOR("SENSOR", "Presión");

    companion object {
        fun fromWire(value: String?): Pad? = entries.firstOrNull { it.wire == value }
    }
}

/** Everything the ESP32 can tell the app, already parsed. */
sealed interface EspMessage {
    data class Level(
        val level: Int,
        val sequenceLength: Int,
        val difficulty: String,
        val timeoutMs: Int,
        val lives: Int,
    ) : EspMessage

    data class Touch(val pad: Pad?, val correct: Boolean, val step: Int, val total: Int, val lives: Int) : EspMessage
    data class Timeout(val step: Int, val total: Int, val lives: Int) : EspMessage
    data class LevelCleared(val level: Int, val lives: Int, val rewardSeconds: Int) : EspMessage
    data class GameOver(val level: Int, val rewardSeconds: Int, val sequence: List<Pad>) : EspMessage

    /** Optional live telemetry (juegito-live/...): one element lit during sequence playback. */
    data class LiveStep(val pad: Pad, val step: Int, val total: Int) : EspMessage

    /** Optional live telemetry: the input window just opened. */
    data class YourTurn(val timeoutMs: Int) : EspMessage
}
