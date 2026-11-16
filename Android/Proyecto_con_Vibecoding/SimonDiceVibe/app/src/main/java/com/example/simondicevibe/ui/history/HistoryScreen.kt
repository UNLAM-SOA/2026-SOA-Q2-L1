package com.example.simondicevibe.ui.history

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.simondicevibe.data.history.EntryKind
import com.example.simondicevibe.data.history.HistoryEntry
import com.example.simondicevibe.data.history.HistoryRepository
import com.example.simondicevibe.data.history.HistoryStats
import com.example.simondicevibe.ui.Factories
import com.example.simondicevibe.ui.HistoryViewModel
import com.example.simondicevibe.ui.components.GameButton
import com.example.simondicevibe.ui.components.GlassCard
import com.example.simondicevibe.ui.components.GradientBackground
import com.example.simondicevibe.ui.components.SectionTitle
import com.example.simondicevibe.ui.components.ShimmerBox
import com.example.simondicevibe.ui.components.StatTile
import com.example.simondicevibe.ui.theme.Bungee
import com.example.simondicevibe.ui.theme.Danger
import com.example.simondicevibe.ui.theme.GlassBorder
import com.example.simondicevibe.ui.theme.PadBlue
import com.example.simondicevibe.ui.theme.PadGold
import com.example.simondicevibe.ui.theme.PadGreen
import com.example.simondicevibe.ui.theme.TextMuted
import com.example.simondicevibe.ui.theme.TextPrimary
import com.example.simondicevibe.ui.theme.Violet
import com.example.simondicevibe.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = viewModel(factory = Factories.history)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.refresh() else vm.permissionDenied()
    }

    fun query(pull: Boolean = false) {
        val needsPermission = Build.VERSION.SDK_INT >= HistoryRepository.ANDROID_17_API &&
            ContextCompat.checkSelfPermission(context, HistoryRepository.PERMISSION_LOCAL_NETWORK) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(HistoryRepository.PERMISSION_LOCAL_NETWORK) else vm.refresh(pull)
    }

    LaunchedEffect(Unit) { query() }

    GradientBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹  Volver",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text("HISTORIAL", fontFamily = Bungee, fontSize = 22.sp, color = TextPrimary)
                Spacer(Modifier.width(12.dp))
            }

            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { query(pull = true) }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        ServerCard(
                            server = state.server,
                            busy = state.loading || state.refreshing,
                            onChange = vm::onServerChange,
                            onQuery = { query() },
                        )
                    }

                    val snapshot = state.snapshot
                    when {
                        state.loading -> {
                            item { SkeletonStats() }
                            items(5) { ShimmerBox(Modifier.fillMaxWidth(), height = 76.dp) }
                        }

                        state.error != null -> item { ErrorCard(state.error.orEmpty(), onRetry = { query() }) }

                        snapshot != null && snapshot.entries.isEmpty() -> item { EmptyCard() }

                        snapshot != null -> {
                            item { StatsHeader(snapshot.stats) }
                            if (snapshot.stats.levelsPerGame.isNotEmpty()) {
                                item { LevelChart(snapshot.stats.levelsPerGame) }
                            }
                            item { SectionTitle("${snapshot.entries.size} mensajes, del más nuevo al más viejo") }
                            items(snapshot.entries, key = { it.id }) { entry ->
                                EntryCard(entry, Modifier.animateItem())
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(server: String, busy: Boolean, onChange: (String) -> Unit, onQuery: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("SERVIDOR NODE-RED", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = server,
                onValueChange = onChange,
                singleLine = true,
                label = { Text("IP:puerto de la PC") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onQuery() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = GlassBorder,
                    focusedLabelColor = Violet,
                    unfocusedLabelColor = TextMuted,
                    cursorColor = Violet,
                ),
                modifier = Modifier.weight(1f),
            )
            GameButton(
                text = if (busy) "…" else "IR",
                onClick = onQuery,
                enabled = !busy,
                colors = listOf(Violet, PadBlue),
                height = 56.dp,
                modifier = Modifier.width(84.dp),
            )
        }
    }
}

@Composable
private fun StatsHeader(stats: HistoryStats) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(stats.games.toString(), "Partidas", PadBlue, Modifier.weight(1f))
        StatTile(stats.bestLevel.toString(), "Mejor nivel", PadGold, Modifier.weight(1f))
        StatTile(stats.accuracyPercent?.let { "$it%" } ?: "–", "Aciertos", PadGreen, Modifier.weight(1f))
    }
}

@Composable
private fun SkeletonStats() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { ShimmerBox(Modifier.weight(1f), height = 84.dp) }
    }
}

/** Bar chart of the level reached in each finished game (oldest to newest). */
@Composable
private fun LevelChart(levels: List<Int>) {
    val progress = remember(levels) { Animatable(0f) }
    LaunchedEffect(levels) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val measurer = rememberTextMeasurer()
    val best = levels.maxOrNull() ?: 1
    val labelStyle = TextStyle(fontSize = 11.sp, color = TextMuted)

    GlassCard(Modifier.fillMaxWidth()) {
        Text("NIVEL ALCANZADO POR PARTIDA", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val labelSpace = 18.dp.toPx()
            val chartH = size.height - labelSpace
            // hairline grid
            for (i in 1..3) {
                val y = chartH * (1f - i / 3f)
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val gap = 8.dp.toPx()
            val barW = ((size.width - gap * (levels.size - 1)) / levels.size).coerceAtMost(46.dp.toPx())
            val total = barW * levels.size + gap * (levels.size - 1)
            val startX = (size.width - total) / 2f
            levels.forEachIndexed { i, level ->
                val h = chartH * (level / best.toFloat()) * progress.value
                val x = startX + i * (barW + gap)
                val isBest = level == best
                val brush = Brush.verticalGradient(
                    if (isBest) listOf(PadGold, PadGold.copy(alpha = 0.55f)) else listOf(Violet, PadBlue.copy(alpha = 0.5f)),
                    startY = chartH - h,
                    endY = chartH,
                )
                drawRoundRect(brush, Offset(x, chartH - h), Size(barW, h), CornerRadius(8.dp.toPx()))
                val text = level.toString()
                val layout = measurer.measure(text, labelStyle.copy(color = if (isBest) PadGold else TextPrimary))
                drawText(layout, topLeft = Offset(x + (barW - layout.size.width) / 2f, (chartH - h - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                val idx = measurer.measure("#${i + 1}", labelStyle)
                drawText(idx, topLeft = Offset(x + (barW - idx.size.width) / 2f, chartH + 3.dp.toPx()))
            }
        }
    }
}

private fun kindStyle(kind: EntryKind): Pair<Color, String> = when (kind) {
    EntryKind.ESP_STATUS, EntryKind.APP_STATUS -> Violet to "●"
    EntryKind.START -> PadBlue to "▶"
    EntryKind.LEVEL -> PadBlue to "↑"
    EntryKind.TOUCH_OK -> PadGreen to "✓"
    EntryKind.TOUCH_BAD -> Danger to "✕"
    EntryKind.TIMEOUT -> Warning to "!"
    EntryKind.CLEARED -> PadGold to "★"
    EntryKind.GAME_OVER -> Danger to "■"
    EntryKind.OTHER -> TextMuted to "?"
}

@Composable
private fun EntryCard(entry: HistoryEntry, modifier: Modifier = Modifier) {
    val (color, glyph) = kindStyle(entry.kind)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, GlassBorder, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)).border(1.dp, color.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            Text(glyph, color = color, fontSize = 18.sp, fontFamily = Bungee)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            entry.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = TextMuted) }
        }
        Spacer(Modifier.width(8.dp))
        Text(entry.time, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Text("✕", fontFamily = Bungee, fontSize = 36.sp, color = Danger)
        Spacer(Modifier.height(8.dp))
        Text("No hay conexión con Node-RED", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.height(16.dp))
        GameButton("REINTENTAR", onRetry, Modifier.fillMaxWidth(), colors = listOf(Danger, Warning), height = 52.dp)
    }
}

@Composable
private fun EmptyCard() {
    GlassCard(Modifier.fillMaxWidth(), padding = 24.dp) {
        Text("Todavía no hay mensajes guardados", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Jugá una partida: cada toque, nivel y game over queda registrado acá por Node-RED.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}
