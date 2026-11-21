package com.example.remoteaudio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import com.example.remoteaudio.service.PlaybackService
import com.example.remoteaudio.service.PlaybackService.Phase
import com.example.remoteaudio.ui.theme.RemoteAudioTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            RemoteAudioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RemoteAudioScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun RemoteAudioScreen(modifier: Modifier = Modifier) {
    val state by PlaybackService.state.collectAsState()
    val context = LocalContext.current
    var host by rememberSaveable {
        mutableStateOf(PlaybackService.savedHost(context))
    }
    var port by rememberSaveable {
        mutableStateOf(PlaybackService.savedPort(context).toString())
    }
    val active = state.phase == Phase.CONNECTING ||
        state.phase == Phase.BUFFERING ||
        state.phase == Phase.PLAYING ||
        state.phase == Phase.RECONNECTING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("远程音频接收器", style = MaterialTheme.typography.headlineSmall)
        Text(
            "在 Linux 上运行 remote-audio-server，把应用输出切换到 RemoteAudio 虚拟设备，然后输入主机 IP 连接。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Linux 主机 IP") },
            placeholder = { Text("例如 192.168.1.100") },
            singleLine = true,
            enabled = !active,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("端口") },
            singleLine = true,
            enabled = !active,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (active) {
                    sendControl(context, PlaybackService.ACTION_DISCONNECT)
                } else {
                    val portNum = port.toIntOrNull() ?: return@Button
                    if (host.isBlank()) return@Button
                    val intent = Intent(context, PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_CONNECT)
                        .putExtra(PlaybackService.EXTRA_HOST, host.trim())
                        .putExtra(PlaybackService.EXTRA_PORT, portNum)
                    ContextCompat.startForegroundService(context, intent)
                }
            },
            enabled = active || (host.isNotBlank() && port.toIntOrNull() != null),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (active) "断开连接" else "连接并播放")
        }

        StatusCard(state)

        Text("音量 ${(state.volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = state.volume,
            onValueChange = { v ->
                val intent = Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_VOLUME)
                    .putExtra(PlaybackService.EXTRA_VOLUME, v)
                context.startService(intent)
            },
        )
    }
}

@Composable
private fun StatusCard(state: PlaybackService.UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val phaseText = when (state.phase) {
                Phase.IDLE -> "未连接"
                Phase.CONNECTING -> "连接中…"
                Phase.BUFFERING -> "缓冲中…"
                Phase.PLAYING -> "播放中"
                Phase.RECONNECTING -> "断线重连中…"
                Phase.ERROR -> "连接出错"
            }
            Text("状态：$phaseText", style = MaterialTheme.typography.titleMedium)
            if (state.endpoint.isNotEmpty()) {
                Text("服务端：${state.endpoint}", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.phase == Phase.PLAYING || state.phase == Phase.BUFFERING) {
                Text(
                    "缓冲深度：${state.bufferMs} ms    已播放帧数：${state.framesPlayed}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error?.let {
                Text(
                    "错误：$it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun sendControl(context: Context, action: String) {
    val intent = Intent(context, PlaybackService::class.java).setAction(action)
    context.startService(intent)
}