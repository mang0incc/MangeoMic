package com.mangeo.mic

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicService : Service() {
    private var isRunning = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val HEARTBEAT_MSG = "MANGOVAR".toByteArray()
    private val DISCONNECT_MSG = "MANGEO_BYE".toByteArray()
    private val KEEP_ALIVE_PC = "MANGOHI"
    private val AUDIO_PORT = 50006

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pcIp = intent?.getStringExtra("PC_IP") ?: return START_NOT_STICKY
        val sensitivity = intent.getFloatExtra("SENSITIVITY", 1.0f)

        if (!isRunning) {
            isRunning = true
            startForegroundService()
            startStreaming(pcIp, sensitivity)
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "MangeoMicChannel"
        val channel = NotificationChannel(channelId, "MangeoMic Active", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MangeoMic Aktif")
            .setContentText("Ses bilgisayara iletiliyor...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startStreaming(pcIp: String, sensitivity: Float) {
        scope.launch {
            var socket: DatagramSocket? = null
            var recorder: AudioRecord? = null
            var lastHiReceivedTime = System.currentTimeMillis()
            var targetAddress: InetAddress? = null

            try {
                targetAddress = InetAddress.getByName(pcIp)
                socket = DatagramSocket(AUDIO_PORT)
                socket.soTimeout = 1000
                launch {
                    val receiveBuf = ByteArray(1024)
                    while (isRunning) {
                        try {
                            val hbPacket = DatagramPacket(HEARTBEAT_MSG, HEARTBEAT_MSG.size, targetAddress, AUDIO_PORT)
                            socket?.send(hbPacket)
                            val receivePacket = DatagramPacket(receiveBuf, receiveBuf.size)
                            socket?.receive(receivePacket)

                            val msg = String(receivePacket.data, 0, receivePacket.length).trim()
                            if (msg == KEEP_ALIVE_PC) {
                                lastHiReceivedTime = System.currentTimeMillis()
                                Log.d("MangeoMic", "PC Canlı Sinyali Alındı")
                            } else if (msg == "MANGEO_BYE") {
                                isRunning = false
                            }
                        } catch (e: Exception) {
                            if (System.currentTimeMillis() - lastHiReceivedTime > 10000) {
                                Log.e("MangeoMic", "Bağlantı koptu: PC'den sinyal gelmiyor.")
                                isRunning = false
                            }
                        }
                        delay(500)
                    }
                    stopSelf()
                }
                val sampleRate = 44100
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val bufferSize = minBufferSize.coerceAtLeast(2048)

                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                val shortBuffer = ShortArray(bufferSize / 2)

                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@launch
                recorder.startRecording()

                while (isRunning) {
                    val read = recorder.read(shortBuffer, 0, shortBuffer.size)
                    if (read > 0 && isRunning) {
                        val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) { // SENI SEVIYORUM DENIZ!!!
                            val sample = (shortBuffer[i] * sensitivity).toInt().coerceIn(-32768, 32767)
                            byteBuffer.putShort(sample.toShort())
                        } // MUHTEMELEN BUNU ASLA GORMEYECEKSIN DENIZ SENI COK SEVIYORUMMMM
                        val packet = DatagramPacket(byteBuffer.array(), byteBuffer.array().size, targetAddress, AUDIO_PORT)
                        socket.send(packet)
                    }
                }

            } catch (e: Exception) {
                Log.e("MangeoMic", "Yayın Hatası: ${e.message}")
            } finally {
                isRunning = false
                try {
                    val byePacket = DatagramPacket(DISCONNECT_MSG, DISCONNECT_MSG.size, targetAddress, AUDIO_PORT)
                    socket?.send(byePacket)
                } catch (e: Exception) {}

                recorder?.apply {
                    if (state == AudioRecord.STATE_INITIALIZED) stop()
                    release()
                }
                socket?.close()
                Log.d("MangeoMic", "Servis Temizlendi.")
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}