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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.Danger
import com.example.simondicevibe.ui.theme.PadGold
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.PadRed
import com.example.simondicevibe.ui.theme.TextMuted
import com.example.simondicevibe.ui.theme.TextPrimary
import com.example.simondicevibe.ui.theme.Violet
import com.example.simondicevibe.ui.theme.Warning
import java.util.Locale

/**
 * Ring that drains over [windowMs] every time [token] changes (the ESP32 reopens the input window).
 * When [running] is false it shows [idleLabel] instead of a number.
 */
@Composable
fun CountdownRing(
    token: Int,
    windowMs: Int,
    running: Boolean,
    idleLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(token, running, windowMs) {
        if (running && windowMs > 0) {
            progress.snapTo(1f)
            progress.animateTo(0f, tween(windowMs, easing = LinearEasing))
        } else {
            progress.animateTo(0f, tween(250))
        }
    }
    val transition = rememberInfiniteTransition(label = "idleRing")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "spin",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            // dark disc so the ring reads well on top of the pads
            drawCircle(Color(0xCC0B0720), radius = this.size.minDimension / 2f)
            drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (running) {
                val p = progress.value
                val color = when {
                    p > 0.5f -> lerp(PadGold, PadGreen, (p - 0.5f) * 2f)
                    else -> lerp(Danger, PadGold, p * 2f)
                }
                drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            } else {
                drawArc(Violet.copy(alpha = 0.9f), spin, 70f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        if (running) {
            Text(
                String.format(Locale.US, "%.1f", progress.value * windowMs / 1000f),
                fontFamily = Bungee,
                fontSize = 24.sp,
                color = TextPrimary,
            )
        } else {
            Text(idleLabel, fontFamily = Bungee, fontSize = 12.sp, color = TextPrimary)
        }
    }
}

private fun heartPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w / 2f, h * 0.92f)
    cubicTo(-w * 0.18f, h * 0.48f, w * 0.08f, -h * 0.08f, w / 2f, h * 0.28f)
    cubicTo(w * 0.92f, -h * 0.08f, w * 1.18f, h * 0.48f, w / 2f, h * 0.92f)
    close()
}

@Composable
private fun Heart(alive: Boolean, size: Dp) {
    val scale = remember { Animatable(1f) }
    val fill by animateFloatAsState(if (alive) 1f else 0f, tween(350), label = "heartFill")
    LaunchedEffect(alive) {
        if (alive) {
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy))
        } else {
            scale.animateTo(1.45f, tween(110))
            scale.animateTo(0.88f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        }
    }
    Canvas(Modifier.size(size).graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
        val path = heartPath(this.size.width, this.size.height)
        if (fill > 0.01f) {
            drawPath(path, Brush.verticalGradient(listOf(lerp(Color.Transparent, Color(0xFFFF8FA3), fill), lerp(Color.Transparent, PadRed, fill))))
        }
        drawPath(path, lerp(Color.White.copy(alpha = 0.35f), PadRed, fill), style = Stroke(2.dp.toPx()))
    }
}

@Composable
fun LivesRow(lives: Int, modifier: Modifier = Modifier, maxLives: Int = 3, heartSize: Dp = 30.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(maxLives) { Heart(alive = it < lives, size = heartSize) }
    }
}

/** Segmented progress bar: one segment per sequence element. */
@Composable
fun SequenceProgress(
    total: Int,
    filled: Int,
    filledColor: Color,
    errorAt: Int,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(filled.toFloat(), tween(250), label = "segments")
    val transition = rememberInfiniteTransition(label = "segmentPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        if (total <= 0) return@Canvas
        val gap = if (total > 20) 2.dp.toPx() else 5.dp.toPx()
        val w = (size.width - gap * (total - 1)) / total
        for (i in 0 until total) {
            val x = i * (w + gap)
            val color = when {
                i + 1 == errorAt -> Danger
                i < animated.toInt() -> filledColor
                i == filled -> filledColor.copy(alpha = pulse)
                else -> Color.White.copy(alpha = 0.12f)
            }
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2f))
        }
    }
}

@Composable
fun DifficultyBadge(difficulty: String, timeoutMs: Int, modifier: Modifier = Modifier) {
    if (difficulty.isBlank()) return
    val (color, label) = when (difficulty.uppercase()) {
        "FACIL" -> PadGreen to "FÁCIL"
        "NORMAL" -> Warning to "NORMAL"
        else -> Danger to "DIFÍCIL"
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        if (timeoutMs > 0) {
            Text("  ·  ${timeoutMs} ms", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        }
    }
}

@Composable
fun StatTile(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier, corner = 20.dp, padding = 14.dp) {
        Text(value, fontFamily = Bungee, fontSize = 28.sp, color = accent)
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
    }
}
