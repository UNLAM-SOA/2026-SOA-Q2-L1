package com.example.soaappandroid

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONException
import org.json.JSONObject

class PartidaActivity : AppCompatActivity() {

    private lateinit var txtNivel: TextView
    private lateinit var txtVidas: TextView
    private lateinit var txtPaso: TextView
    private lateinit var txtDificultad: TextView
    private lateinit var txtMensaje: TextView
    private lateinit var contenedorPuntos: LinearLayout
    private lateinit var corazones: List<ImageView>
    private var pasoUltimoError = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_partida)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        txtNivel = findViewById(R.id.txtNivel)
        txtVidas = findViewById(R.id.txtVidas)
        txtPaso = findViewById(R.id.txtPaso)
        txtDificultad = findViewById(R.id.txtDificultad)
        txtMensaje = findViewById(R.id.txtMensaje)
        contenedorPuntos = findViewById(R.id.contenedorPuntos)
        corazones = listOf(
            findViewById(R.id.imgVida1),
            findViewById(R.id.imgVida2),
            findViewById(R.id.imgVida3)
        )

        mostrarCartel("Esperando a la ESP32...", "#FFF4DB", "#6B4A00")

        ConexionMqtt.alRecibirMensaje = { nombre, texto ->
            runOnUiThread { procesarMensaje(nombre, texto) }
        }
        ConexionMqtt.publicarStart()
    }

    override fun onDestroy() {
        super.onDestroy()
        ConexionMqtt.alRecibirMensaje = null
    }

    private fun procesarMensaje(nombre: String, texto: String) {
        try {
            val datos = JSONObject(texto)
            when (nombre) {
                "nivel" -> mostrarNivel(
                    datos.getInt("nivel"),
                    datos.getInt("largo_secuencia"),
                    datos.getString("dificultad"),
                    datos.getInt("timeout_ms"),
                    datos.getInt("vidas")
                )
                "toque" -> mostrarToque(
                    datos.getBoolean("correcto"),
                    datos.getInt("paso"),
                    datos.getInt("de"),
                    datos.getInt("vidas")
                )
                "timeout" -> mostrarTimeout(
                    datos.getInt("paso"),
                    datos.getInt("de"),
                    datos.getInt("vidas")
                )
                "nivel_superado" -> mostrarNivelSuperado(datos.getInt("nivel"))
                "game_over" -> {
                    val lista = datos.getJSONArray("secuencia")
                    val secuencia = List(lista.length()) { lista.getString(it) }

                    terminarPartida(datos.getInt("nivel"), secuencia, datos.optInt("segundos_video", 0))
                }
            }
        } catch (e: JSONException) {
            Log.e("PartidaActivity", "Mensaje inválido en $nombre: $texto", e)
        }
    }

    private fun mostrarCartel(texto: String, colorFondo: String, colorTexto: String) {
        txtMensaje.text = texto
        txtMensaje.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colorFondo))
        txtMensaje.setTextColor(Color.parseColor(colorTexto))
    }

    private fun mostrarVidas(vidas: Int) {
        txtVidas.text = "Vidas: $vidas"
        for (i in corazones.indices) {
            val imagen = if (i < vidas) R.drawable.ic_corazon_lleno else R.drawable.ic_corazon_vacio
            corazones[i].setImageResource(imagen)
        }
    }

    private fun mostrarPuntos(largo: Int, hechos: Int, puntoSiguiente: Int) {
        contenedorPuntos.removeAllViews()
        contenedorPuntos.weightSum = maxOf(largo, 8).toFloat()

        for (i in 0 until largo) {
            val imagen = when {
                i < hechos -> R.drawable.punto_hecho
                i == hechos -> puntoSiguiente
                else -> R.drawable.punto_pendiente
            }
            val punto = ImageView(this)
            punto.setImageResource(imagen)
            punto.importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            contenedorPuntos.addView(
                punto,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
    }

    private fun mostrarNivel(nivel: Int, largo: Int, dificultad: String, timeoutMs: Int, vidas: Int) {
        txtNivel.text = "Nivel $nivel"
        mostrarVidas(vidas)
        txtPaso.text = "Paso 0 de $largo"
        mostrarPuntos(largo, 0, R.drawable.punto_pendiente)
        txtDificultad.text = "Dificultad: $dificultad · $timeoutMs ms por toque"
        mostrarCartel("Mirá la secuencia en el tablero", "#FFF4DB", "#6B4A00")
    }

    private fun mostrarToque(correcto: Boolean, paso: Int, de: Int, vidas: Int) {
        txtPaso.text = "Paso $paso de $de"
        mostrarVidas(vidas)
        if (correcto) {
            mostrarPuntos(de, paso, R.drawable.punto_actual)
            mostrarCartel("¡Bien!", "#ECEEFC", "#2A3699")
        } else {
            mostrarPuntos(de, paso - 1, R.drawable.punto_error)
            mostrarCartel("Perdiste una vida", "#FDECEC", "#9B1C1C")
            pasoUltimoError = paso
        }
    }

    private fun mostrarTimeout(paso: Int, de: Int, vidas: Int) {
        txtPaso.text = "Paso $paso de $de"
        mostrarVidas(vidas)
        mostrarPuntos(de, paso - 1, R.drawable.punto_error)
        mostrarCartel("Se acabó el tiempo", "#FDECEC", "#9B1C1C")
        pasoUltimoError = paso
    }

    private fun mostrarNivelSuperado(nivel: Int) {
        mostrarCartel("¡Nivel $nivel superado!", "#ECEEFC", "#2A3699")
    }

    private fun terminarPartida(nivel: Int, secuencia: List<String>, segundosVideo: Int) {
        val intent = Intent(this, ResultadoActivity::class.java)
        intent.putExtra(ResultadoActivity.EXTRA_NIVEL, nivel)
        intent.putExtra(ResultadoActivity.EXTRA_SEGUNDOS_VIDEO, segundosVideo)
        intent.putStringArrayListExtra(ResultadoActivity.EXTRA_SECUENCIA, ArrayList(secuencia))
        intent.putExtra(ResultadoActivity.EXTRA_PASO_FALLADO, pasoUltimoError)
        startActivity(intent)
        finish()
    }
}
