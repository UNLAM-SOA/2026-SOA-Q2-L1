package com.example.simondicevibe.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.simondicevibe.ui.theme.PadBlue
import com.example.simondicevibe.ui.theme.PadGold
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.PadRed
import com.example.simondicevibe.ui.theme.Violet
import kotlin.random.Random

private class Particle(
    val x: Float,
    val phase: Float,
    val speed: Float,
    val size: Float,
    val color: Color,
    val spin: Float,
    val sway: Float,
)

/** Pure Canvas confetti rain (no library). */
@Composable
fun Confetti(modifier: Modifier = Modifier, count: Int = 110, durationMs: Int = 5200) {
    val particles = remember {
        val rnd = Random(7)
        val palette = listOf(PadRed, PadBlue, PadGreen, PadGold, Violet, Color.White)
        List(count) {
            Particle(
                x = rnd.nextFloat(),
                phase = rnd.nextFloat(),
                speed = 0.7f + rnd.nextFloat() * 0.8f,
                size = 6f + rnd.nextFloat() * 8f,
                color = palette[rnd.nextInt(palette.size)],
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                sway = 12f + rnd.nextFloat() * 28f,
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "confetti")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing)),
        label = "t",
    )
    Canvas(modifier.fillMaxSize()) {
        particles.forEach { p ->
            val progress = (t * p.speed + p.phase) % 1f
            val y = -20f + progress * (size.height + 40f)
            val x = p.x * size.width + kotlin.math.sin(progress * 12f + p.phase * 6f) * p.sway
            rotate(p.spin * progress, pivot = Offset(x, y)) {
                drawRect(
                    p.color.copy(alpha = (1f - progress * 0.35f).coerceIn(0f, 1f)),
                    topLeft = Offset(x - p.size / 2f, y - p.size / 2f),
                    size = Size(p.size, p.size * 0.55f),
                )
            }
        }
    }
}

/** Number that counts up from 0 whenever [target] changes. */
@Composable
fun AnimatedCounter(target: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, durationMs: Int = 1100) {
    val value by animateIntAsState(target, tween(durationMs), label = "counter")
    Text(value.toString(), style = style, color = color, modifier = modifier)
}

/** Moving highlight used by the loading skeletons. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, height: Dp = 72.dp, corner: Dp = 20.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shift",
    )
    val base = Color.White.copy(alpha = 0.07f)
    val light = Color.White.copy(alpha = 0.2f)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(base, light, base), start = Offset(shift, 0f), end = Offset(shift + 420f, 120f)))
            .height(height),
    )
}
