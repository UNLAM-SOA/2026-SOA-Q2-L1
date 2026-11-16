package com.example.simondicevibe

import com.example.simondicevibe.data.mqtt.EspMessageParser
import com.example.simondicevibe.domain.EspMessage
import com.example.simondicevibe.domain.GameState
import com.example.simondicevibe.domain.LitSource
import com.example.simondicevibe.domain.Pad
import com.example.simondicevibe.domain.Phase
import com.example.simondicevibe.domain.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Payloads below are copied verbatim from the snprintf() formats in the ESP32 sketch
 * (which are in turn identical to the original student sketch / Node-RED history).
 */
class ContractTest {

    @Test
    fun parsesEveryEspTopic() {
        val level = EspMessageParser.parse(
            "nivel",
            """{"nivel":1,"largo_secuencia":3,"largo_inicial":3,"dificultad":"NORMAL","timeout_ms":3000,"vidas":3}""",
        )
        assertEquals(EspMessage.Level(1, 3, "NORMAL", 3000, 3), level)

        val touch = EspMessageParser.parse("toque", """{"elemento":"SENSOR","correcto":false,"paso":2,"de":3,"vidas":2}""")
        assertEquals(EspMessage.Touch(Pad.SENSOR, false, 2, 3, 2), touch)

        assertEquals(EspMessage.Timeout(1, 3, 2), EspMessageParser.parse("timeout", """{"paso":1,"de":3,"vidas":2}"""))
        assertEquals(EspMessage.LevelCleared(1, 3, 5), EspMessageParser.parse("nivel_superado", """{"nivel":1,"vidas":3,"segundos_video":5}"""))
        assertEquals(
            EspMessage.GameOver(2, 5, listOf(Pad.ROJO, Pad.AZUL, Pad.VERDE, Pad.SENSOR)),
            EspMessageParser.parse("game_over", """{"nivel":2,"segundos_video":5,"secuencia":["ROJO","AZUL","VERDE","SENSOR"]}"""),
        )
    }

    @Test
    fun parsesOptionalLiveTelemetryAndRejectsGarbage() {
        assertEquals(EspMessage.LiveStep(Pad.VERDE, 2, 4), EspMessageParser.parse("paso", """{"fase":"secuencia","elemento":"VERDE","paso":2,"de":4}"""))
        assertEquals(EspMessage.YourTurn(1500), EspMessageParser.parse("paso", """{"fase":"turno","timeout_ms":1500}"""))
        assertNull(EspMessageParser.parse("nivel", "not json"))
        assertNull(EspMessageParser.parse("conexion", "online"))
    }

    @Test
    fun reducerFollowsAFullRound() {
        var s = GameState()
        s = s.reduce(EspMessage.Level(1, 3, "NORMAL", 3000, 3))
        assertEquals(Phase.WATCHING, s.phase)

        s = s.reduce(EspMessage.LiveStep(Pad.ROJO, 1, 3))
        assertEquals(Pad.ROJO, s.lit?.pad)
        assertEquals(LitSource.ESP_SEQUENCE, s.lit?.source)

        s = s.reduce(EspMessage.YourTurn(3000))
        assertEquals(Phase.YOUR_TURN, s.phase)
        val token = s.turnToken

        s = s.reduce(EspMessage.Touch(Pad.ROJO, true, 1, 3, 3))
        assertEquals(1, s.completedSteps)
        assertEquals(token + 1, s.turnToken) // ring restarts after every correct touch

        s = s.reduce(EspMessage.Touch(Pad.AZUL, false, 2, 3, 2))
        assertEquals(2, s.lives)
        assertEquals(2, s.failedStep)
        assertEquals(LitSource.PLAYER_WRONG, s.lit?.source)

        s = s.reduce(EspMessage.GameOver(1, 0, listOf(Pad.ROJO, Pad.VERDE, Pad.AZUL)))
        assertEquals(Phase.OVER, s.phase)
        assertNotNull(s.result)
        assertEquals(2, s.result?.failedStep)
    }
}
