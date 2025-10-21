package com.voicedev.vocedev.voice.record.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.icons.Icons
import androidx.compose.material3.icons.filled.Lock
import androidx.compose.material3.icons.filled.LockOpen
import androidx.compose.material3.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voicedev.vocedev.voice.record.VoiceRecorderComponent
import kotlinx.coroutines.delay

@Composable
fun RecorderScreen(
    model: VoiceRecorderComponent.Model,
    onIntent: (VoiceRecorderComponent.Intent) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        TimerHeader(model = model)
        PeakMeter(peakLevelDb = model.peakLevelDb)
        GainControl(gain = model.gain, onGainChanged = { onIntent(VoiceRecorderComponent.Intent.UpdateGain(it)) })
        NoiseSuppressSwitch(
            enabled = model.noiseSuppressionEnabled,
            onToggle = { onIntent(VoiceRecorderComponent.Intent.UpdateNoiseSuppress(it)) }
        )
        RecordButton(
            isRecording = model.isRecording,
            isPaused = model.isPaused,
            isLocked = model.isLocked,
            onPress = {
                if (!model.isRecording) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onIntent(VoiceRecorderComponent.Intent.Start)
                } else if (model.isPaused) {
                    onIntent(VoiceRecorderComponent.Intent.Resume)
                } else {
                    onIntent(VoiceRecorderComponent.Intent.Pause)
                }
            },
            onRelease = {
                if (model.isRecording && !model.isLocked && !model.isPaused) {
                    onIntent(VoiceRecorderComponent.Intent.Finish)
                }
            },
            onCancel = {
                if (model.isRecording && !model.isLocked) {
                    onIntent(VoiceRecorderComponent.Intent.Cancel)
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onIntent(VoiceRecorderComponent.Intent.ToggleLock)
                }
            ) {
                Icon(
                    imageVector = if (model.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (model.isLocked) "Unlock recording" else "Lock recording"
                )
            }
            Button(
                onClick = {
                    if (model.isRecording && model.isPaused) {
                        onIntent(VoiceRecorderComponent.Intent.Resume)
                    } else if (model.isRecording) {
                        onIntent(VoiceRecorderComponent.Intent.Finish)
                    } else {
                        onIntent(VoiceRecorderComponent.Intent.Start)
                    }
                },
                enabled = model.error == null
            ) {
                Text(
                    text = when {
                        model.isRecording && model.isPaused -> "Resume"
                        model.isRecording -> "Finish"
                        else -> "Record"
                    }
                )
            }
        }
        model.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TimerHeader(model: VoiceRecorderComponent.Model) {
    val formatted = remember(model.elapsedMs) { formatElapsed(model.elapsedMs) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatted,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        model.tempFilePath?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PeakMeter(peakLevelDb: Float) {
    val normalized = ((peakLevelDb + 60f) / 60f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Peak level: %.1f dB".format(peakLevelDb))
        LinearProgressIndicator(progress = normalized, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GainControl(gain: Float, onGainChanged: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Input gain: %.1fx".format(gain))
        Slider(
            value = gain,
            onValueChange = onGainChanged,
            valueRange = 0.1f..3f,
            steps = 20
        )
    }
}

@Composable
private fun NoiseSuppressSwitch(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Noise suppression")
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    isPaused: Boolean,
    isLocked: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val color = when {
        !isRecording -> MaterialTheme.colorScheme.primary
        isPaused -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val gestureDescription = when {
        !isRecording -> "Press and hold to record"
        isLocked -> "Recording locked"
        isPaused -> "Recording paused"
        else -> "Recording in progress"
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(color)
            .semantics {
                role = Role.Button
                contentDescription = gestureDescription
            }
            .padding(8.dp)
            .detectPressGesture(
                onPress = {
                    pressed = true
                    onPress()
                },
                onRelease = {
                    pressed = false
                    onRelease()
                },
                onCancel = {
                    pressed = false
                    onCancel()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = Color.White
        )
    }
}

private fun Modifier.detectPressGesture(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit
): Modifier = pointerInput(Unit) {
    detectTapGestures(
        onPress = {
            onPress()
            val released = tryAwaitRelease()
            if (released) {
                onRelease()
            } else {
                onCancel()
            }
        }
    )
}

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ((elapsedMs % 1000) / 10).toInt()
    return "%02d:%02d.%02d".format(minutes, seconds, millis)
}

@Preview
@Composable
private fun RecorderScreenPreview() {
    RecorderScreen(
        model = VoiceRecorderComponent.Model(isRecording = true, elapsedMs = 42_000, peakLevelDb = -12f),
        onIntent = {}
    )
}

