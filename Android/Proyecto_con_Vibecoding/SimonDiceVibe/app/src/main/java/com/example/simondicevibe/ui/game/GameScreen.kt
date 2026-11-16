package com.example.simondicevibe.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.simondicevibe.domain.Banner
import com.example.simondicevibe.domain.LitSource
import com.example.simondicevibe.domain.Pad
import com.example.simondicevibe.domain.Phase
import com.example.simondicevibe.ui.Factories
import com.example.simondicevibe.ui.GameViewModel
import com.example.simondicevibe.ui.components.CountdownRing
import com.example.simondicevibe.ui.components.DifficultyBadge
import com.example.simondicevibe.ui.components.GlassCard
import com.example.simondicevibe.ui.components.GradientBackground
import com.example.simondicevibe.ui.components.LivesRow
import com.example.simondicevibe.ui.components.PadFlash
import com.example.simondicevibe.ui.components.PadGrid
import com.example.simondicevibe.ui.components.SequenceProgress
import com.example.simondicevibe.ui.components.toFlash
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.Danger
import com.example.simondicevibe.ui.theme.PadBlue
import com.example.simondicevibe.ui.theme.PadGold
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.TextMuted
import com.example.simondicevibe.ui.theme.TextPrimary
import com.example.simondicevibe.ui.theme.Violet
import com.example.simondicevibe.ui.theme.Warning
import kotlinx.coroutines.delay

@Composable
fun GameScreen(onExit: () -> Unit, onFinished: () -> Unit, vm: GameViewModel = viewModel(factory = Factories.game)) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    // Haptics mirror the physical board: soft tick for sequence, confirm/reject for the player's touches.
    LaunchedEffect(s.lit?.token) {
        when (s.lit?.source) {
            LitSource.ESP_SEQUENCE -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            LitSource.PLAYER_OK -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            LitSource.PLAYER_WRONG -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
    }
    LaunchedEffect(s.phase) {
        when (s.phase) {
            Phase.CLEARED -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            Phase.OVER -> {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                delay(1900) // let the last heart break and the ESP32 melody play
                onFinished()
            }
            else -> Unit
        }
    }

    val watching = s.phase == Phase.WATCHING && s.banner == Banner.WATCH
    val filled = if (watching) s.watchStep else s.completedSteps
    val progressColor = if (watching) Violet else PadGreen
    val errorAt = if (s.banner == Banner.WRONG || s.banner == Banner.TIMEOUT) s.failedStep else 0
    val lit = s.lit

    GradientBackground {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹  Salir",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onExit).padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                RewardPill(s.rewardSeconds)
            }

            Spacer(Modifier.height(8.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NIVEL", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                        AnimatedContent(
                            targetState = s.level,
                            transitionSpec = {
                                (slideInVertically(tween(350)) { it } + fadeIn(tween(350))) togetherWith
                                    (slideOutVertically(tween(350)) { -it } + fadeOut(tween(200)))
                            },
                            label = "level",
                        ) { level ->
                            Text(if (level > 0) level.toString() else "–", fontFamily = Bungee, fontSize = 46.sp, color = TextPrimary)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        LivesRow(s.lives)
                        Spacer(Modifier.height(8.dp))
                        DifficultyBadge(s.difficulty, s.timeoutMs)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            BannerPill(s.banner)
            Spacer(Modifier.height(16.dp))
            SequenceProgress(s.sequenceLength, filled, progressColor, errorAt)
            Spacer(Modifier.height(6.dp))
            Text(caption(s.phase, s.banner, filled, s.sequenceLength), style = MaterialTheme.typography.labelMedium, color = TextMuted)

            Spacer(Modifier.weight(1f))

            PadGrid(
                flashOf = { pad: Pad -> if (lit?.pad == pad) lit.source.toFlash() else PadFlash.NONE },
                overlay = {
                    CountdownRing(
                        token = s.turnToken,
                        windowMs = s.timeoutMs,
                        running = s.phase == Phase.YOUR_TURN,
                        idleLabel = when (s.phase) {
                            Phase.WAITING -> "···"
                            Phase.WATCHING -> "MIRÁ"
                            Phase.CLEARED -> "¡OK!"
                            Phase.OVER -> "FIN"
                            Phase.YOUR_TURN -> ""
                        },
                    )
                },
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

private fun caption(phase: Phase, banner: Banner, filled: Int, total: Int): String = when {
    total == 0 -> "Esperando el primer nivel"
    phase == Phase.WATCHING && banner == Banner.WATCH -> "Mirando: elemento $filled de $total"
    phase == Phase.YOUR_TURN -> "Tu turno: paso ${filled + 1} de $total"
    phase == Phase.CLEARED -> "Completaste los $total pasos"
    phase == Phase.OVER -> "Se acabaron las vidas"
    else -> "Preparando la secuencia de $total elementos"
}

@Composable
private fun RewardPill(seconds: Int) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(PadGold.copy(alpha = 0.14f)).border(1.dp, PadGold.copy(alpha = 0.5f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("▶", color = PadGold, fontSize = 11.sp)
        Text("Premio: $seconds s", style = MaterialTheme.typography.labelLarge, color = PadGold)
    }
}

@Composable
private fun BannerPill(banner: Banner) {
    AnimatedContent(
        targetState = banner,
        transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(120))) },
        label = "banner",
    ) { b ->
        val (text, color) = when (b) {
            Banner.WAITING_ESP -> "Esperando a la ESP32…" to Warning
            Banner.WATCH -> "Mirá la secuencia en el tablero" to Violet
            Banner.TURN -> "¡Tu turno! Repetila" to PadBlue
            Banner.NICE -> "¡Bien!" to PadGreen
            Banner.WRONG -> "Perdiste una vida" to Danger
            Banner.TIMEOUT -> "Se acabó el tiempo" to Danger
            Banner.CLEARED -> "¡Nivel superado!" to PadGreen
            Banner.OVER -> "Game over" to Danger
        }
        val shape = RoundedCornerShape(50)
        Box(
            Modifier.clip(shape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.6f), shape)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}
