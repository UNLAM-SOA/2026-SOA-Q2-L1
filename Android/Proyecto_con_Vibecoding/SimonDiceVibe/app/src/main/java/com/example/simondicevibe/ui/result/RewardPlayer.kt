package com.example.simondicevibe.ui.result

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.simondicevibe.R
import com.example.simondicevibe.ui.theme.GlassBorder
import com.example.simondicevibe.ui.theme.TextMuted

private enum class RewardStatus { LOADING, PLAYING, PAUSED_BY_HAND, ENDED, ERROR }

/**
 * Plays `res/raw/premio.mp4` for exactly [seconds] (clipped, like the original app) with Media3.
 * Same proximity-sensor behaviour as the original: cover the sensor to pause, uncover to resume.
 */
@Composable
fun RewardPlayer(seconds: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(RewardStatus.LOADING) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val proximity = remember { sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.Builder()
                .setUri(Uri.parse("android.resource://${context.packageName}/${R.raw.premio}"))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(seconds * 1000L).build(),
                )
                .build()
            setMediaItem(item)
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (status == RewardStatus.LOADING) status = RewardStatus.PLAYING
                    Player.STATE_ENDED -> status = RewardStatus.ENDED
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                status = RewardStatus.ERROR
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause when the screen is not visible.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            if (event == Lifecycle.Event.ON_RESUME && status == RewardStatus.PLAYING) player.play()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Proximity sensor: hand near = pause.
    DisposableEffect(player, proximity) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val near = event.values[0] < event.sensor.maximumRange
                when {
                    status == RewardStatus.ENDED || status == RewardStatus.ERROR -> Unit
                    near -> {
                        player.pause()
                        status = RewardStatus.PAUSED_BY_HAND
                    }
                    status == RewardStatus.PAUSED_BY_HAND -> {
                        player.play()
                        status = RewardStatus.PLAYING
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        proximity?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black).border(1.dp, GlassBorder, shape),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        Text(
            when (status) {
                RewardStatus.LOADING -> "Cargando el video…"
                RewardStatus.PLAYING -> "Tu premio: $seconds s de video" + if (proximity != null) " · Tapá el sensor para pausar" else ""
                RewardStatus.PAUSED_BY_HAND -> "En pausa: sacá la mano del sensor para seguir"
                RewardStatus.ENDED -> "Fin del premio"
                RewardStatus.ERROR -> "No se pudo reproducir el video"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (status == RewardStatus.ERROR) MaterialTheme.colorScheme.error else TextMuted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
