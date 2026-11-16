package com.example.soaappandroid

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ResultadoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NIVEL = "nivel"
        const val EXTRA_SECUENCIA = "secuencia"
        const val EXTRA_PASO_FALLADO = "paso_fallado"
        const val EXTRA_SEGUNDOS_VIDEO = "segundos_video"

        private const val NOMBRE_VIDEO = "premio"

        private const val INTERVALO_REVISION_MS = 200L
    }

    private lateinit var txtNivelAlcanzado: TextView
    private lateinit var txtSecuencia: TextView
    private lateinit var txtFallo: TextView
    private lateinit var contenedorChips: LinearLayout
    private lateinit var btnJugarDeNuevo: Button
    private lateinit var btnVolverInicio: Button
    private lateinit var videoPremio: VideoView
    private lateinit var txtVideo: TextView

    private var segundosPremio = 0
    private var limiteMs = 0
    private var videoListo = false
    private var premioTerminado = false
    private var manoCerca = false

    private val handler = Handler(Looper.getMainLooper())
    private val revisarFin = object : Runnable {
        override fun run() {
            if (videoPremio.currentPosition >= limiteMs) {
                terminarPremio()
            } else {
                handler.postDelayed(this, INTERVALO_REVISION_MS)
            }
        }
    }

    private lateinit var sensorManager: SensorManager
    private var sensorProximidad: Sensor? = null
    private val escuchaProximidad = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val cerca = evento.values[0] < evento.sensor.maximumRange
            if (cerca != manoCerca) {
                manoCerca = cerca
                aplicarProximidad()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, precision: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_resultado)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        txtNivelAlcanzado = findViewById(R.id.txtNivelAlcanzado)
        txtSecuencia = findViewById(R.id.txtSecuencia)
        txtFallo = findViewById(R.id.txtFallo)
        contenedorChips = findViewById(R.id.contenedorChips)
        btnJugarDeNuevo = findViewById(R.id.btnJugarDeNuevo)
        btnVolverInicio = findViewById(R.id.btnVolverInicio)
        videoPremio = findViewById(R.id.videoPremio)
        txtVideo = findViewById(R.id.txtVideo)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorProximidad = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        val nivel = intent.getIntExtra(EXTRA_NIVEL, 0)
        val secuencia = intent.getStringArrayListExtra(EXTRA_SECUENCIA) ?: arrayListOf()
        val pasoFallado = intent.getIntExtra(EXTRA_PASO_FALLADO, 0)
        val segundosVideo = intent.getIntExtra(EXTRA_SEGUNDOS_VIDEO, 0)

        txtNivelAlcanzado.text = "Llegaste al nivel $nivel"
        txtSecuencia.text = textoSecuencia(secuencia, pasoFallado)
        txtFallo.text = "Fallaste en el paso $pasoFallado"
        mostrarChips(secuencia, pasoFallado)
        reproducirPremio(segundosVideo)

        btnJugarDeNuevo.setOnClickListener {
            startActivity(Intent(this, PartidaActivity::class.java))
            finish()
        }

        btnVolverInicio.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        sensorProximidad?.let {
            sensorManager.registerListener(escuchaProximidad, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(escuchaProximidad)
        handler.removeCallbacks(revisarFin)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(revisarFin)
        videoPremio.stopPlayback()
    }

    private fun reproducirPremio(segundos: Int) {
        if (segundos <= 0) {
            txtVideo.text = "No sumaste segundos de video"
            return
        }

        val idVideo = resources.getIdentifier(NOMBRE_VIDEO, "raw", packageName)
        if (idVideo == 0) {
            txtVideo.text = "Falta el video: res/raw/$NOMBRE_VIDEO.mp4"
            return
        }

        segundosPremio = segundos
        txtVideo.text = "Cargando el video..."
        videoPremio.setVideoURI(Uri.parse("android.resource://$packageName/$idVideo"))

        videoPremio.setOnPreparedListener { reproductor ->
            if (premioTerminado) return@setOnPreparedListener
            limiteMs = minOf(segundos * 1000, reproductor.duration)
            videoListo = true
            aplicarProximidad()
        }
        videoPremio.setOnCompletionListener { terminarPremio() }
        videoPremio.setOnErrorListener { _, _, _ ->
            txtVideo.text = "No se pudo reproducir el video"
            true
        }
    }

    private fun aplicarProximidad() {
        if (!videoListo || premioTerminado) return

        if (manoCerca) {
            videoPremio.pause()
            handler.removeCallbacks(revisarFin)
            txtVideo.text = "En pausa: sacá la mano del sensor para seguir"
        } else {
            videoPremio.start()
            handler.removeCallbacks(revisarFin)
            handler.post(revisarFin)
            val ayuda = if (sensorProximidad != null) " · Tapá el sensor para pausar" else ""
            txtVideo.text = "Tu premio: $segundosPremio s de video$ayuda"
        }
    }

    private fun terminarPremio() {
        premioTerminado = true
        handler.removeCallbacks(revisarFin)
        videoPremio.pause()
        txtVideo.text = "Fin del premio"
    }

    private fun textoSecuencia(secuencia: List<String>, pasoFallado: Int): String {
        return secuencia
            .mapIndexed { indice, elemento ->
                if (indice + 1 == pasoFallado) "[$elemento]" else elemento
            }
            .joinToString(" · ")
    }

    private fun mostrarChips(secuencia: List<String>, pasoFallado: Int) {
        for ((indice, elemento) in secuencia.withIndex()) {
            val chip = layoutInflater.inflate(R.layout.item_chip, contenedorChips, false)
            val txtLetra = chip.findViewById<TextView>(R.id.txtChipLetra)
            val txtNumero = chip.findViewById<TextView>(R.id.txtChipNumero)

            txtLetra.text = elemento.first().toString()
            txtLetra.backgroundTintList = ColorStateList.valueOf(colorDe(elemento))
            txtNumero.text = (indice + 1).toString()

            if (indice + 1 == pasoFallado) {
                chip.setBackgroundResource(R.drawable.bg_cartel)
                chip.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FDECEC"))
                txtNumero.setTextColor(Color.parseColor("#9B1C1C"))
                txtNumero.setTypeface(null, Typeface.BOLD)
            }
            contenedorChips.addView(chip)
        }
    }

    private fun colorDe(elemento: String): Int {
        val color = when (elemento) {
            "ROJO" -> "#C93434"
            "AZUL" -> "#2C63D2"
            "VERDE" -> "#1E7B47"
            else -> "#33354A" // SENSOR
        }
        return Color.parseColor(color)
    }
}
