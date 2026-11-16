package com.example.soaappandroid

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtConexion: TextView
    private lateinit var btnIniciar: Button
    private var conectado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        txtConexion = findViewById(R.id.txtConexion)
        btnIniciar = findViewById(R.id.btnIniciar)

        btnIniciar.setOnClickListener {
            startActivity(Intent(this, PartidaActivity::class.java))
        }

        findViewById<Button>(R.id.btnHistorial).setOnClickListener {
            startActivity(Intent(this, HistorialActivity::class.java))
        }

        conectado = ConexionMqtt.esp32Conectada
        actualizarPantalla()

        ConexionMqtt.alCambiarEstado = { estado ->
            runOnUiThread {
                conectado = estado
                actualizarPantalla()
            }
        }
        ConexionMqtt.conectar()
    }

    override fun onDestroy() {
        super.onDestroy()
        ConexionMqtt.alCambiarEstado = null
    }

    private fun actualizarPantalla() {
        txtConexion.text = if (conectado) "Conexión establecida con ESP32" else "Esperando conexión con ESP32"
        btnIniciar.isEnabled = conectado
    }
}