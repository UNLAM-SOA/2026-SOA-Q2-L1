package com.example.simondicevibe.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.simondicevibe.data.mqtt.BrokerStatus
import com.example.simondicevibe.data.mqtt.MqttContract
import com.example.simondicevibe.ui.Factories
import com.example.simondicevibe.ui.HomeViewModel
import com.example.simondicevibe.ui.components.AnimatedLogo
import com.example.simondicevibe.ui.components.GameButton
import com.example.simondicevibe.ui.components.GhostButton
import com.example.simondicevibe.ui.components.GradientBackground
import com.example.simondicevibe.ui.components.StatusChip
import com.example.simondicevibe.ui.components.TitleBrush
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.Danger
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.TextMuted
import com.example.simondicevibe.ui.theme.Warning

@Composable
fun HomeScreen(onPlay: () -> Unit, onHistory: () -> Unit, vm: HomeViewModel = viewModel(factory = Factories.home)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val (brokerText, brokerColor, brokerPulse) = when (state.broker) {
        BrokerStatus.CONNECTED -> Triple("Conectado", PadGreen, false)
        BrokerStatus.CONNECTING -> Triple("Conectando…", Warning, true)
        BrokerStatus.DISCONNECTED -> Triple("Sin conexión", Danger, true)
    }
    val (espText, espColor) = if (state.espOnline) "En línea" to PadGreen else "Esperando…" to Warning

    GradientBackground {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                StatusChip("BROKER MQTT", brokerText, brokerColor, brokerPulse)
                StatusChip("ESP32", espText, espColor, pulsing = true)
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(800)) + slideInVertically(tween(800)) { it / 5 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedLogo()
                    Spacer(Modifier.height(26.dp))
                    Text("SIMÓN", style = TextStyle(brush = TitleBrush, fontFamily = Bungee, fontSize = 54.sp, lineHeight = 54.sp))
                    Text("DICE", style = TextStyle(brush = TitleBrush, fontFamily = Bungee, fontSize = 54.sp, lineHeight = 54.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Mirá la secuencia en tu ESP32\ny repetila sin equivocarte",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.weight(1.2f))

            GameButton(
                text = if (state.espOnline) "JUGAR" else "ESPERANDO A LA ESP32…",
                onClick = onPlay,
                enabled = state.espOnline,
                showPlay = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            GhostButton("Ver historial", onHistory, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text(
                "${MqttContract.PREFIX}  ·  broker.hivemq.com",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted.copy(alpha = 0.7f),
            )
        }
    }
}
