package com.example.simondicevibe.data.mqtt

import com.example.simondicevibe.domain.EspMessage
import com.example.simondicevibe.domain.Pad
import org.json.JSONException
import org.json.JSONObject

/** Turns `esp32/<suffix>` JSON payloads into [EspMessage]s. Returns null for anything unknown or malformed. */
object EspMessageParser {

    fun parse(suffix: String, json: String): EspMessage? {
        return try {
            val o = JSONObject(json)
            when (suffix) {
                "nivel" -> EspMessage.Level(
                    level = o.getInt("nivel"),
                    sequenceLength = o.getInt("largo_secuencia"),
                    difficulty = o.getString("dificultad"),
                    timeoutMs = o.getInt("timeout_ms"),
                    lives = o.getInt("vidas"),
                )

                "toque" -> EspMessage.Touch(
                    pad = Pad.fromWire(o.optString("elemento")),
                    correct = o.getBoolean("correcto"),
                    step = o.getInt("paso"),
                    total = o.getInt("de"),
                    lives = o.getInt("vidas"),
                )

                "timeout" -> EspMessage.Timeout(o.getInt("paso"), o.getInt("de"), o.getInt("vidas"))

                "nivel_superado" -> EspMessage.LevelCleared(
                    level = o.getInt("nivel"),
                    lives = o.getInt("vidas"),
                    rewardSeconds = o.optInt("segundos_video", 0),
                )

                "game_over" -> {
                    val array = o.getJSONArray("secuencia")
                    EspMessage.GameOver(
                        level = o.getInt("nivel"),
                        rewardSeconds = o.optInt("segundos_video", 0),
                        sequence = List(array.length()) { Pad.fromWire(array.getString(it)) }.filterNotNull(),
                    )
                }

                "paso" -> when (o.optString("fase")) {
                    "secuencia" -> Pad.fromWire(o.optString("elemento"))?.let {
                        EspMessage.LiveStep(it, o.getInt("paso"), o.getInt("de"))
                    }
                    "turno" -> EspMessage.YourTurn(o.getInt("timeout_ms"))
                    else -> null
                }

                else -> null
            }
        } catch (e: JSONException) {
            null
        }
    }
}
