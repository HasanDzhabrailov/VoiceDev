package com.voicedev.vocedev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkivanov.decompose.defaultComponentContext
import com.voicedev.vocedev.voice.record.VoiceRecorderComponent
import com.voicedev.vocedev.voice.record.VoiceRecorderComponentImpl
import com.voicedev.vocedev.voice.record.audio.AndroidAudioSource
import com.voicedev.vocedev.voice.record.audio.AudioGateway
import com.voicedev.vocedev.voice.record.audio.OpusEncoder
import com.voicedev.vocedev.voice.record.ui.RecorderScreen
import com.voicedev.vocedev.ui.theme.VoiceDevTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

        private val permissionFlow = MutableStateFlow(false)
        private lateinit var recorderComponent: VoiceRecorderComponent

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                enableEdgeToEdge()
                updatePermissionState()
                val permissionLauncher = registerForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                ) {
                        updatePermissionState()
                }
                recorderComponent = VoiceRecorderComponentImpl(
                        componentContext = defaultComponentContext(),
                        audioGateway = AudioGateway(
                                audioSource = AndroidAudioSource(Dispatchers.IO),
                                encoderFactory = { file -> OpusEncoder(file, Dispatchers.IO) },
                                cacheDirProvider = { cacheDir },
                                ioDispatcher = Dispatchers.IO
                        ),
                        permissionFlow = permissionFlow.asStateFlow(),
                        hasPermission = ::hasRecordPermission
                )
                setContent {
                        VoiceDevTheme {
                                Surface(modifier = Modifier.fillMaxSize()) {
                                        val model by recorderComponent.model.collectAsStateWithLifecycle()
                                        RecorderScreen(
                                                model = model,
                                                onIntent = { intent ->
                                                        if (intent is VoiceRecorderComponent.Intent.Start && !hasRecordPermission()) {
                                                                permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                                                        } else {
                                                                recorderComponent.onIntent(intent)
                                                        }
                                                }
                                        )
                                }
                        }
                }
        }

        override fun onResume() {
                super.onResume()
                updatePermissionState()
        }

        private fun hasRecordPermission(): Boolean {
                return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        private fun updatePermissionState() {
                permissionFlow.value = hasRecordPermission()
        }
}