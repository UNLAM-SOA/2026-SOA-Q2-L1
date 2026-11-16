package com.example.simondicevibe.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.DeepBlue
import com.example.simondicevibe.ui.theme.GlassBorder
import com.example.simondicevibe.ui.theme.Indigo
import com.example.simondicevibe.ui.theme.Magenta
import com.example.simondicevibe.ui.theme.Night
import com.example.simondicevibe.ui.theme.PadBlue
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.PadRed
import com.example.simondicevibe.ui.theme.TextMuted
import com.example.simondicevibe.ui.theme.TextPrimary
import com.example.simondicevibe.ui.theme.Violet

/** Deep vertical gradient with slowly drifting colour blobs behind everything. */
@Composable
fun GradientBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "background")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, Indigo, DeepBlue)))
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.radialGradient(
                        listOf(Magenta.copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(w * (0.1f + 0.5f * drift), h * 0.10f),
                        radius = w * 0.9f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(PadBlue.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(w * (0.95f - 0.5f * drift), h * 0.85f),
                        radius = w * 1.0f,
                    ),
                )
            },
        content = content,
    )
}

/** Translucent "glass" surface with a hairline border. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, GlassBorder, shape)
            .padding(padding),
        content = content,
    )
}

/** Small dot that emits an expanding ring while [pulsing]. */
@Composable
fun PulsingDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dot")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)),
        label = "phase",
    )
    Canvas(modifier.size(18.dp)) {
        val r = size.minDimension / 2f
        if (pulsing) {
            drawCircle(color.copy(alpha = (1f - phase) * 0.55f), radius = r * (0.45f + 0.55f * phase))
        }
        drawCircle(color, radius = r * 0.45f)
    }
}

@Composable
fun StatusChip(label: String, value: String, color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color, pulsing)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(value, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = TextPrimary)
        }
    }
}

/** Big gradient pill button. When disabled it turns into a muted, non-clickable placeholder. */
@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: List<Color> = listOf(PadGreen, Color(0xFF16B3A4)),
    showPlay: Boolean = false,
    height: Dp = 68.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val transition = rememberInfiniteTransition(label = "buttonPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                val s = pressScale * if (enabled) 1f + 0.025f * pulse else 1f
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (enabled) {
                    drawRoundRect(
                        colors.first().copy(alpha = 0.18f + 0.22f * pulse),
                        topLeft = Offset(-10f, -6f),
                        size = androidx.compose.ui.geometry.Size(size.width + 20f, size.height + 24f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                    )
                }
            }
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(colors)
                else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.05f))),
            )
            .border(1.dp, if (enabled) Color.White.copy(alpha = 0.4f) else GlassBorder, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (showPlay && enabled) {
                Canvas(Modifier.size(22.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.15f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(size.width * 0.15f, size.height)
                        close()
                    }
                    drawPath(p, Night)
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text,
                fontFamily = Bungee,
                fontSize = if (enabled) 26.sp else 15.sp,
                fontWeight = FontWeight.Normal,
                color = if (enabled) Night else TextMuted,
            )
        }
    }
}

/** Quiet secondary button with a glass look. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, GlassBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** Gradient accent for headline text. */
val TitleBrush = Brush.horizontalGradient(listOf(PadRed, Magenta, Violet, PadBlue, PadGreen))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = TextMuted,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    )
}
