package com.example.simondicevibe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.simondicevibe.ui.navigation.SimonNavHost
import com.example.simondicevibe.ui.theme.SimonTheme

/** Single activity: everything else is Compose + Navigation. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimonTheme {
                SimonNavHost()
            }
        }
    }
}
