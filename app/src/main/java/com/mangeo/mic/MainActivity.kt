package com.mangeo.mic

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mangeo.mic.ui.theme.MangeoMicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

class MainActivity : ComponentActivity() {
    private val DISCOVER_MSG = "MANGEO_DISCOVER"
    private val HI_MSG = "MANGEO_HI"
    private val OK_MSG = "MANGEO_OK"
    private val PAIR_PORT = 50004

    private var isStreaming by mutableStateOf(false)
    private var isMatched by mutableStateOf(false)
    private var micSensitivity by mutableStateOf(0.7f)

    private var currentLatency by mutableStateOf(0L)
    private val latencyHistory = mutableStateListOf<Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MangeoMicTheme {
                val scope = rememberCoroutineScope()
                var hasPermission by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

                LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.RECORD_AUDIO) }

                LaunchedEffect(isStreaming) {
                    if (isStreaming) {
                        while (isStreaming) {
                            delay(1500)
                            if (isMatched && !isServiceRunning()) {
                                isStreaming = false
                                isMatched = false
                                latencyHistory.clear()
                                currentLatency = 0
                                break
                            }
                        }
                    }
                }

                MangeoMicScreen(
                    isStreaming = isStreaming,
                    isMatched = isMatched,
                    sensitivity = micSensitivity,
                    latency = currentLatency,
                    latencyHistory = latencyHistory,
                    onSensitivityChange = { micSensitivity = it },
                    onToggle = {
                        if (hasPermission) {
                            if (!isStreaming) {
                                isStreaming = true
                                scope.launch(Dispatchers.IO) { startHandshakeProcess() }
                            } else {
                                stopMicService()
                                isStreaming = false
                                isMatched = false
                                latencyHistory.clear()
                                currentLatency = 0
                            }
                        } else {
                            launcher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (MicService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private suspend fun startHandshakeProcess() {
        var pairSocket: DatagramSocket? = null
        try {
            pairSocket = DatagramSocket(PAIR_PORT)
            pairSocket.soTimeout = 1000
            val buffer = ByteArray(256)

            while (isStreaming && !isMatched) {
                val startTime = System.currentTimeMillis()
                val packet = DatagramPacket(buffer, buffer.size)

                try {
                    pairSocket.receive(packet)
                    val received = String(packet.data, 0, packet.length).trim()

                    if (received == DISCOVER_MSG) {
                        val reply = HI_MSG.toByteArray()
                        val replyPacket = DatagramPacket(reply, reply.size, packet.address, PAIR_PORT)
                        pairSocket.send(replyPacket)
                    } else if (received == OK_MSG) {
                        currentLatency = System.currentTimeMillis() - startTime
                        updateLatencyHistory(currentLatency.toFloat())

                        val pcIp = packet.address.hostAddress
                        isMatched = true
                        startMicService(pcIp)
                    }
                } catch (e: SocketTimeoutException) {
                    delay(500)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isStreaming = false
        } finally {
            pairSocket?.close()
        }
    }

    private fun updateLatencyHistory(newValue: Float) {
        latencyHistory.add(newValue)
        if (latencyHistory.size > 20) {
            latencyHistory.removeAt(0)
        }
    }

    private fun startMicService(pcIp: String?) {
        val intent = Intent(this, MicService::class.java).apply {
            putExtra("PC_IP", pcIp)
            putExtra("SENSITIVITY", micSensitivity)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMicService() {
        val intent = Intent(this, MicService::class.java)
        stopService(intent)
    }
}

@Composable
fun LatencyGraph(latencyHistory: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (latencyHistory.size < 2) return@Canvas

        val path = Path()
        val width = size.width
        val height = size.height
        val maxVal = (latencyHistory.maxOrNull() ?: 100f).coerceAtLeast(50f)
        val stepX = width / (latencyHistory.size - 1)

        latencyHistory.forEachIndexed { i, value ->
            val x = i * stepX
            val y = height - (value / maxVal * height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(path = path, color = color, style = Stroke(width = 4.dp.toPx()))
    }
} // DARKNESS IMPRISONING ME

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangeoMicScreen(
    isStreaming: Boolean,
    isMatched: Boolean,
    sensitivity: Float,
    latency: Long,
    latencyHistory: List<Float>,
    onSensitivityChange: (Float) -> Unit,
    onToggle: () -> Unit
) {
    val dynamicPrimary = MaterialTheme.colorScheme.primary
    val warningColor = Color(0xFFFFB100) // ALL THAT I SEE ABSOLUTE HORROR

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CenterAlignedTopAppBar(
            title = { Text("MangeoMic", style = MaterialTheme.typography.headlineMedium) }
        )

        Box( // I CANNOT LIVE I CANNOT DIE
            modifier = Modifier.fillMaxWidth().height(120.dp).padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isMatched) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Gecikme: ${latency}ms", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    LatencyGraph(latencyHistory, Modifier.fillMaxSize())
                }
            } else {
                Text("Bağlantı bekleniyor...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        val statusText = when {
            !isStreaming -> "Başlatmak için dokun"
            isStreaming && !isMatched -> "Eşleşme Bekleniyor..."
            else -> "PC ile Eşleşti - Yayın Açık"
        }

        val buttonColor = when {
            !isStreaming -> dynamicPrimary
            isStreaming && !isMatched -> warningColor  // TRAPPED IN MYSELF BODY MY HOLDING CELL
            else -> Color(0xFF4CAF50)
        }

        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(140.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = buttonColor)
        ) {
            Icon(
                painter = painterResource(id = if (isStreaming) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now),
                contentDescription = null,
                modifier = Modifier.size(70.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(  // LANDMINE HAS TAKEN MY SIGHT
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            color = if (isStreaming && !isMatched) warningColor else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.weight(1f))  // TAKEN MY SPEECH TAKEN MY HEARING

        Card(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Mikrofon Hassasiyeti", style = MaterialTheme.typography.labelLarge)
                    Text("%${(sensitivity * 100).toInt()}", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = sensitivity,
                    onValueChange = onSensitivityChange,  // TAKEN MY ARMS TAKEN MY LEGS
                    valueRange = 0f..2f
                )
            }
        }
    } // TAKEN MY SOUL LEFT ME WITH LIFE IN HELL
}