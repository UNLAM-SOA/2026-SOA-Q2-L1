package com.example.soaappandroid

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class HistorialActivity : AppCompatActivity() {

    companion object {
        private const val PREFERENCIAS = "historial"
        private const val CLAVE_SERVIDOR = "servidor"
        private const val SERVIDOR_POR_DEFECTO = "192.168.0.25:1880"
        private const val PUERTO_NODE_RED = 1880
        private const val RUTA = "/juegito/historial?limite=100"
        private const val TIMEOUT_MS = 5000

        private const val PERMISO_RED_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val API_ANDROID_17 = 37
    }

    private val pedirPermisoRedLocal =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                consultar()
            } else {
                mostrarCartel(
                    "Sin el permiso de red local la app no puede hablar con Node-RED en tu PC",
                    "#FDECEC", "#9B1C1C"
                )
            }
        }

    private lateinit var edtServidor: EditText
    private lateinit var btnConsultar: Button
    private lateinit var txtEstado: TextView
    private lateinit var contenedorHistorial: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_historial)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        edtServidor = findViewById(R.id.edtServidor)
        btnConsultar = findViewById(R.id.btnConsultar)
        txtEstado = findViewById(R.id.txtEstado)
        contenedorHistorial = findViewById(R.id.contenedorHistorial)

        val preferencias = getSharedPreferences(PREFERENCIAS, MODE_PRIVATE)
        edtServidor.setText(preferencias.getString(CLAVE_SERVIDOR, SERVIDOR_POR_DEFECTO))

        btnConsultar.setOnClickListener { consultar() }

        edtServidor.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_GO) {
                consultar()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.btnVolver).setOnClickListener { finish() }

        consultar()
    }


    private fun consultar() {
        val servidor = normalizarServidor(edtServidor.text.toString())
        if (servidor.isEmpty()) {
            mostrarCartel("Escribí la IP de la PC donde corre Node-RED", "#FDECEC", "#9B1C1C")
            return
        }
        edtServidor.setText(servidor)
        getSharedPreferences(PREFERENCIAS, MODE_PRIVATE).edit()
            .putString(CLAVE_SERVIDOR, servidor)
            .apply()
        esconderTeclado()

        if (faltaPermisoRedLocal()) {
            pedirPermisoRedLocal.launch(PERMISO_RED_LOCAL)
            return
        }

        btnConsultar.isEnabled = false
        mostrarCartel("Consultando $servidor...", "#FFF4DB", "#6B4A00")

        Thread {
            val resultado = try {
                Result.success(pedirHistorial("http://$servidor$RUTA"))
            } catch (e: Exception) {
                Result.failure(e)
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                btnConsultar.isEnabled = true
                resultado
                    .onSuccess { mostrarHistorial(it) }
                    .onFailure { mostrarError(servidor, it) }
            }
        }.start()
    }

    private fun faltaPermisoRedLocal(): Boolean {
        return Build.VERSION.SDK_INT >= API_ANDROID_17 &&
            ContextCompat.checkSelfPermission(this, PERMISO_RED_LOCAL) != PackageManager.PERMISSION_GRANTED
    }

    private fun normalizarServidor(texto: String): String {
        var servidor = texto.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (servidor.isNotEmpty() && !servidor.contains(":")) {
            servidor += ":$PUERTO_NODE_RED"
        }
        return servidor
    }


    private fun pedirHistorial(direccion: String): List<JSONObject> {
        val conexion = URL(direccion).openConnection() as HttpURLConnection
        try {
            conexion.connectTimeout = TIMEOUT_MS
            conexion.readTimeout = TIMEOUT_MS

            val codigo = conexion.responseCode
            if (codigo != HttpURLConnection.HTTP_OK) {
                throw IOException("Node-RED respondió $codigo")
            }
            val texto = conexion.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(texto).getJSONArray("items")
            return List(items.length()) { items.getJSONObject(it) }
        } finally {
            conexion.disconnect()
        }
    }

    private fun mostrarHistorial(items: List<JSONObject>) {
        contenedorHistorial.removeAllViews()
        if (items.isEmpty()) {
            mostrarCartel("Todavía no hay mensajes guardados", "#FFF4DB", "#6B4A00")
            return
        }
        mostrarCartel("${items.size} mensajes, del más nuevo al más viejo", "#ECEEFC", "#2A3699")

        for (item in items) {
            val fila = layoutInflater.inflate(R.layout.item_historial, contenedorHistorial, false)
            fila.findViewById<TextView>(R.id.txtHora).text = horaLocal(item.optString("fecha"))
            fila.findViewById<TextView>(R.id.txtDescripcion).text =
                describir(item.optString("topico"), item.opt("payload"))
            contenedorHistorial.addView(fila)
        }
    }

    private fun mostrarError(servidor: String, error: Throwable) {
        contenedorHistorial.removeAllViews()
        val detalle = if (error is JSONException) "la respuesta no es el historial" else error.message
        mostrarCartel(
            "No se pudo consultar $servidor ($detalle). ¿Node-RED está corriendo y el celular está en la misma WiFi?",
            "#FDECEC", "#9B1C1C"
        )
    }

    private fun describir(topico: String, payload: Any?): String {
        val datos = payload as? JSONObject
        return when (topico) {
            "esp32/conexion" -> "ESP32 $payload"
            "app/conexion" -> "App $payload"
            "app/start" -> "La app pidió empezar una partida"
            "esp32/nivel" -> if (datos == null) "$payload" else
                "Nivel ${datos.optInt("nivel")}: ${datos.optInt("largo_secuencia")} colores, " +
                    "${datos.optString("dificultad")}, ${datos.optInt("timeout_ms")} ms por toque"
            "esp32/toque" -> if (datos == null) "$payload" else
                "Toque ${datos.optString("elemento")} " +
                    (if (datos.optBoolean("correcto")) "correcto" else "incorrecto") +
                    " (paso ${datos.optInt("paso")} de ${datos.optInt("de")}), vidas: ${datos.optInt("vidas")}"
            "esp32/timeout" -> if (datos == null) "$payload" else
                "Se acabó el tiempo en el paso ${datos.optInt("paso")} de ${datos.optInt("de")}, " +
                    "vidas: ${datos.optInt("vidas")}"
            "esp32/nivel_superado" -> if (datos == null) "$payload" else
                "Nivel ${datos.optInt("nivel")} superado, ${datos.optInt("segundos_video")} s de video"
            "esp32/game_over" -> if (datos == null) "$payload" else
                "Game over en el nivel ${datos.optInt("nivel")}, ${datos.optInt("segundos_video")} s de video"
            else -> "$topico: $payload"
        }
    }

    private fun horaLocal(fechaUtc: String): String {
        val entrada = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        entrada.timeZone = TimeZone.getTimeZone("UTC")
        val salida = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        return try {
            entrada.parse(fechaUtc)?.let { salida.format(it) } ?: fechaUtc
        } catch (e: ParseException) {
            fechaUtc
        }
    }

    private fun mostrarCartel(texto: String, colorFondo: String, colorTexto: String) {
        txtEstado.text = texto
        txtEstado.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colorFondo))
        txtEstado.setTextColor(Color.parseColor(colorTexto))
    }

    private fun esconderTeclado() {
        val teclado = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        teclado.hideSoftInputFromWindow(edtServidor.windowToken, 0)
        edtServidor.clearFocus()
    }
}
