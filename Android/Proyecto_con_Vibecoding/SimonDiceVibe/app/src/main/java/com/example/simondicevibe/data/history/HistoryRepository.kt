package com.example.simondicevibe.data.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class EntryKind { ESP_STATUS, APP_STATUS, START, LEVEL, TOUCH_OK, TOUCH_BAD, TIMEOUT, CLEARED, GAME_OVER, OTHER }

data class HistoryEntry(
    val id: Long,
    val time: String,
    val kind: EntryKind,
    val title: String,
    val detail: String?,
)

data class HistoryStats(
    val games: Int,
    val bestLevel: Int,
    val rewardSeconds: Int,
    val accuracyPercent: Int?,
    /** Level reached in each finished game, oldest first (max 12). */
    val levelsPerGame: List<Int>,
)

data class HistorySnapshot(val entries: List<HistoryEntry>, val stats: HistoryStats)

/**
 * Reads the Node-RED history endpoint exactly like the original app:
 * `GET http://<host>:1880/juegito/historial?limite=100`, host remembered in SharedPreferences.
 */
class HistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    suspend fun fetch(server: String): HistorySnapshot = withContext(Dispatchers.IO) {
        val items = requestItems("http://$server$PATH")
        val entries = items.map(::toEntry)
        HistorySnapshot(entries, computeStats(items))
    }

    private fun requestItems(address: String): List<JSONObject> {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Node-RED respondió $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONObject(text).getJSONArray("items")
            return List(array.length()) { array.getJSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun computeStats(items: List<JSONObject>): HistoryStats {
        val gameOvers = items.filter { it.optString("topico") == "esp32/game_over" }.mapNotNull { it.optJSONObject("payload") }
        val touches = items.filter { it.optString("topico") == "esp32/toque" }.mapNotNull { it.optJSONObject("payload") }
        val levels = gameOvers.map { it.optInt("nivel") }
        val correct = touches.count { it.optBoolean("correcto") }
        return HistoryStats(
            games = gameOvers.size,
            bestLevel = levels.maxOrNull() ?: 0,
            rewardSeconds = gameOvers.sumOf { it.optInt("segundos_video") },
            accuracyPercent = if (touches.isEmpty()) null else correct * 100 / touches.size,
            levelsPerGame = levels.reversed().takeLast(12), // API is newest-first
        )
    }

    private fun toEntry(item: JSONObject): HistoryEntry {
        val topic = item.optString("topico")
        val payload = item.opt("payload")
        val data = payload as? JSONObject
        val (kind, title, detail) = describe(topic, payload, data)
        return HistoryEntry(item.optLong("id"), formatTime(item.optString("fecha")), kind, title, detail)
    }

    private fun describe(topic: String, payload: Any?, d: JSONObject?): Triple<EntryKind, String, String?> = when (topic) {
        "esp32/conexion" -> Triple(EntryKind.ESP_STATUS, "ESP32 $payload", null)
        "app/conexion" -> Triple(EntryKind.APP_STATUS, "App $payload", null)
        "app/start" -> Triple(EntryKind.START, "Inicio de partida", "La app pidió empezar una partida")
        "esp32/nivel" -> if (d == null) other(topic, payload) else Triple(
            EntryKind.LEVEL,
            "Nivel ${d.optInt("nivel")}",
            "${d.optInt("largo_secuencia")} colores, ${d.optString("dificultad")}, ${d.optInt("timeout_ms")} ms por toque",
        )
        "esp32/toque" -> if (d == null) other(topic, payload) else Triple(
            if (d.optBoolean("correcto")) EntryKind.TOUCH_OK else EntryKind.TOUCH_BAD,
            "Toque ${d.optString("elemento")} " + if (d.optBoolean("correcto")) "correcto" else "incorrecto",
            "Paso ${d.optInt("paso")} de ${d.optInt("de")}, vidas: ${d.optInt("vidas")}",
        )
        "esp32/timeout" -> if (d == null) other(topic, payload) else Triple(
            EntryKind.TIMEOUT,
            "Se acabó el tiempo",
            "Paso ${d.optInt("paso")} de ${d.optInt("de")}, vidas: ${d.optInt("vidas")}",
        )
        "esp32/nivel_superado" -> if (d == null) other(topic, payload) else Triple(
            EntryKind.CLEARED,
            "Nivel ${d.optInt("nivel")} superado",
            "${d.optInt("segundos_video")} s de video de premio",
        )
        "esp32/game_over" -> if (d == null) other(topic, payload) else Triple(
            EntryKind.GAME_OVER,
            "Game over en el nivel ${d.optInt("nivel")}",
            "${d.optInt("segundos_video")} s de video de premio",
        )
        else -> other(topic, payload)
    }

    private fun other(topic: String, payload: Any?) = Triple(EntryKind.OTHER, topic, payload?.toString())

    private fun formatTime(utc: String): String = try {
        TIME_FORMAT.format(Instant.parse(utc))
    } catch (e: Exception) {
        utc
    }

    companion object {
        const val DEFAULT_SERVER = "192.168.0.25:1880"
        const val NODE_RED_PORT = 1880
        const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val ANDROID_17_API = 37

        private const val PREFS = "historial"
        private const val KEY_SERVER = "servidor"
        private const val PATH = "/juegito/historial?limite=100"
        private const val TIMEOUT_MS = 5000
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault())

        /** Same normalisation as the original: strip scheme/trailing slash, default Node-RED port. */
        fun normalizeServer(text: String): String {
            var s = text.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (s.isNotEmpty() && !s.contains(":")) s += ":$NODE_RED_PORT"
            return s
        }
    }
}
