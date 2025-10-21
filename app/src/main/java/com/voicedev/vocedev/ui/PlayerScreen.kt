package com.voicedev.vocedev.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicedev.vocedev.player.VoicePlayerComponent
import kotlin.math.roundToInt
import kotlin.math.max

@Composable
fun PlayerScreen(component: VoicePlayerComponent, onPlay: () -> Unit) {
    val model by component.model.collectAsStateWithLifecycle()
    PlayerUI(model = model, onPlay = onPlay, onIntent = component::onIntent)
}

@Composable
fun PlayerUI(
    model: VoicePlayerComponent.Model,
    onPlay: () -> Unit,
    onIntent: (VoicePlayerComponent.Intent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    val speeds = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WaveformPreview(peaks = model.waveform)

        val sliderValue = if (model.durationMs > 0) {
            (model.positionMs.toFloat() / model.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Slider(
            value = sliderValue,
            onValueChange = { fraction ->
                val target = (model.durationMs * fraction).roundToInt().toLong()
                onIntent(VoicePlayerComponent.Intent.SeekTo(target))
            }
        )

        Text(
            text = buildString {
                append(formatTime(model.positionMs))
                append(" / ")
                append(formatTime(model.durationMs))
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
        )

        if (!model.error.isNullOrBlank()) {
            Text(
                text = model.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    if (model.isPlaying) {
                        onIntent(VoicePlayerComponent.Intent.Pause)
                    } else {
                        onPlay()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (model.isPlaying) "Pause" else "Play")
            }

            TextButton(onClick = { showSpeedMenu = true }) {
                Text(text = "${model.speed}x")
            }

            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                speeds.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${speed}x") },
                        onClick = {
                            showSpeedMenu = false
                            onIntent(VoicePlayerComponent.Intent.SetSpeed(speed))
                        }
                    )
                }
            }

            TextButton(onClick = { onIntent(VoicePlayerComponent.Intent.ToggleSpeakerphone) }) {
                Text("Speaker")
            }
        }
    }
}

@Composable
private fun WaveformPreview(peaks: List<Int>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        if (peaks.isEmpty()) {
            val midY = size.height / 2f
            drawLine(
                color = MaterialTheme.colorScheme.outline,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 2f
            )
            return@Canvas
        }

        val barCount = max(peaks.size, 1)
        val barWidth = size.width / barCount
        peaks.forEachIndexed { index, peak ->
            val normalized = (peak / 255f).coerceIn(0f, 1f)
            val barHeight = size.height * normalized
            val top = (size.height - barHeight) / 2f
            drawRect(
                color = MaterialTheme.colorScheme.primary,
                topLeft = Offset(index * barWidth, top),
                size = Size(barWidth.coerceAtLeast(1f), barHeight)
            )
        }
    }
}

private fun formatTime(positionMs: Long): String {
    if (positionMs <= 0) return "00:00"
    val totalSeconds = positionMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
