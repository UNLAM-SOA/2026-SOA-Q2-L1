package com.example.simondicevibe.ui.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.simondicevibe.domain.GameResult
import com.example.simondicevibe.ui.components.AnimatedCounter
import com.example.simondicevibe.ui.components.Confetti
import com.example.simondicevibe.ui.components.GameButton
import com.example.simondicevibe.ui.components.GhostButton
import com.example.simondicevibe.ui.components.GlassCard
import com.example.simondicevibe.ui.components.GradientBackground
import com.example.simondicevibe.ui.components.TitleBrush
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.Danger
import com.example.simondicevibe.ui.theme.PadBlue
import com.example.simondicevibe.ui.theme.PadGold
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.TextMuted
import com.example.simondicevibe.ui.theme.TextPrimary
import com.example.simondicevibe.ui.theme.color

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(result: GameResult, onPlayAgain: () -> Unit, onHome: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val celebrate = result.rewardSeconds > 0

    GradientBackground {
        if (celebrate) Confetti(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("FIN DE LA PARTIDA", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            Text(
                if (celebrate) "¡Buen juego!" else "¡Seguí practicando!",
                style = TextStyle(brush = TitleBrush, fontFamily = Bungee, fontSize = 36.sp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))

            AnimatedVisibility(shown, enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 4 }) {
                GlassCard(Modifier.fillMaxWidth(), padding = 22.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LLEGASTE AL NIVEL", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        AnimatedCounter(
                            target = result.level,
                            style = TextStyle(fontFamily = Bungee, fontSize = 96.sp, lineHeight = 100.sp),
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MiniStat(result.rewardSeconds, "s de premio", PadGold)
                            MiniStat(result.sequence.size, "colores en la secuencia", PadBlue)
                            MiniStat(result.failedStep, "paso en que fallaste", Danger)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("LA SECUENCIA", style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.sequence.forEachIndexed { index, pad ->
                    val failed = index + 1 == result.failedStep
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(pad.color())
                            .border(if (failed) 3.dp else 1.dp, if (failed) Color.White else Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", fontFamily = Bungee, fontSize = 13.sp, color = Color(0xFF0B0720))
                    }
                }
            }
            if (result.failedStep > 0) {
                Text(
                    "Fallaste en el paso ${result.failedStep} (el que tiene el borde blanco).",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("TU PREMIO", style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (celebrate) {
                RewardPlayer(result.rewardSeconds)
            } else {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("No sumaste segundos de video", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
            }

            Spacer(Modifier.height(24.dp))
            GameButton("JUGAR DE NUEVO", onPlayAgain, Modifier.fillMaxWidth(), colors = listOf(PadGreen, Color(0xFF16B3A4)), height = 62.dp)
            Spacer(Modifier.height(12.dp))
            GhostButton("Volver al inicio", onHome, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MiniStat(value: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        AnimatedCounter(value, TextStyle(fontFamily = Bungee, fontSize = 26.sp), color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.width(88.dp))
    }
}
