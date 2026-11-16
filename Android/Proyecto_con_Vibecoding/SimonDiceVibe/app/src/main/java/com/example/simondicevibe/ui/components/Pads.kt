package com.example.simondicevibe.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.simondicevibe.domain.LitSource
import com.example.simondicevibe.domain.Pad
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.Danger
import com.example.simondicevibe.ui.theme.color

enum class PadFlash { NONE, SEQUENCE, OK, WRONG }

fun LitSource.toFlash(): PadFlash = when (this) {
    LitSource.ESP_SEQUENCE -> PadFlash.SEQUENCE
    LitSource.PLAYER_OK -> PadFlash.OK
    LitSource.PLAYER_WRONG -> PadFlash.WRONG
}

/**
 * One Simon pad. Glows, bounces and (on a wrong answer) shakes in sync with what the ESP32 reports.
 */
@Composable
fun SimonPad(pad: Pad, flash: PadFlash, modifier: Modifier = Modifier) {
    val base = pad.color()
    val lit = flash != PadFlash.NONE
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 60 else 320), label = "glow")
    val scale by animateFloatAsState(
        if (lit) 1.07f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash == PadFlash.WRONG) {
            repeat(3) {
                shake.animateTo(12f, tween(45))
                shake.animateTo(-12f, tween(45))
            }
            shake.animateTo(0f, tween(45))
        }
    }
    val shape = RoundedCornerShape(30.dp)
    val borderColor = if (flash == PadFlash.WRONG) Danger else Color.White
    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = shake.value
            }
            .drawBehind {
                if (glow > 0.01f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf((if (flash == PadFlash.WRONG) Danger else base).copy(alpha = 0.6f * glow), Color.Transparent),
                            center = center,
                            radius = size.minDimension * 0.95f,
                        ),
                        radius = size.minDimension * 0.95f,
                    )
                }
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        lerp(base.copy(alpha = 0.34f), base, glow),
                        lerp(base.copy(alpha = 0.12f), base.copy(alpha = 0.7f), glow),
                    ),
                ),
            )
            .border(2.dp, lerp(Color.White.copy(alpha = 0.12f), borderColor.copy(alpha = 0.95f), glow), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (pad == Pad.SENSOR) {
            Canvas(Modifier.size(84.dp)) {
                val stroke = Stroke(width = 3.dp.toPx())
                for (i in 1..3) {
                    drawCircle(
                        Color.White.copy(alpha = 0.25f + 0.5f * glow - i * 0.05f),
                        radius = size.minDimension / 2f * (i / 3f),
                        style = stroke,
                    )
                }
            }
        }
        Text(
            text = if (flash == PadFlash.WRONG) "✕" else pad.label.uppercase(),
            fontFamily = Bungee,
            fontSize = if (flash == PadFlash.WRONG) 44.sp else 15.sp,
            color = Color.White.copy(alpha = 0.55f + 0.45f * glow),
        )
    }
}

/** 2x2 grid of pads; [overlay] is drawn dead-centre (countdown ring). */
@Composable
fun PadGrid(
    flashOf: (Pad) -> PadFlash,
    modifier: Modifier = Modifier,
    gap: Dp = 18.dp,
    overlay: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SimonPad(Pad.ROJO, flashOf(Pad.ROJO), Modifier.weight(1f))
                SimonPad(Pad.AZUL, flashOf(Pad.AZUL), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SimonPad(Pad.VERDE, flashOf(Pad.VERDE), Modifier.weight(1f))
                SimonPad(Pad.SENSOR, flashOf(Pad.SENSOR), Modifier.weight(1f))
            }
        }
        overlay()
    }
}

/** Animated logo: four pads that light up in a loop while the whole thing floats. */
@Composable
fun AnimatedLogo(modifier: Modifier = Modifier, size: Dp = 170.dp) {
    val transition = rememberInfiniteTransition(label = "logo")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "cycle",
    )
    val float by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "float",
    )
    val pads = listOf(Pad.ROJO, Pad.AZUL, Pad.SENSOR, Pad.VERDE) // clockwise order
    Canvas(modifier.size(size).graphicsLayer { translationY = float * density }) {
        val gap = this.size.minDimension * 0.06f
        val cell = (this.size.minDimension - gap) / 2f
        val radius = CornerRadius(cell * 0.28f)
        pads.forEachIndexed { i, pad ->
            val col = if (i == 0 || i == 3) 0 else 1
            val row = if (i < 2) 0 else 1
            val topLeft = Offset(col * (cell + gap), row * (cell + gap))
            val distance = ((cycle - i + 4f) % 4f)
            val glow = (1f - distance).coerceIn(0f, 1f)
            val color = pad.color()
            if (glow > 0f) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.55f * glow), Color.Transparent), center = topLeft + Offset(cell / 2, cell / 2), radius = cell * 1.1f),
                    radius = cell * 1.1f,
                    center = topLeft + Offset(cell / 2, cell / 2),
                )
            }
            drawRoundRect(
                Brush.linearGradient(
                    listOf(lerp(color.copy(alpha = 0.45f), color, glow), lerp(color.copy(alpha = 0.2f), color.copy(alpha = 0.8f), glow)),
                    start = topLeft,
                    end = topLeft + Offset(cell, cell),
                ),
                topLeft = topLeft,
                size = Size(cell, cell),
                cornerRadius = radius,
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.15f + 0.6f * glow),
                topLeft = topLeft,
                size = Size(cell, cell),
                cornerRadius = radius,
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}
