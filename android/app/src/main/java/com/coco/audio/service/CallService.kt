package com.coco.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.coco.audio.MainActivity
import com.coco.audio.audio.AudioManager
import com.coco.audio.net.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台通话服务：接通后启动录音/播放，把麦克风编码后的帧发出、把收到的帧解码播放。
 */
class CallService : Service() {
    companion object {
        const val ACTION_START = "com.coco.audio.START"
        const val ACTION_STOP = "com.coco.audio.STOP"
        const val EXTRA_DEVICE_ID = "device_id"
        private const val CHANNEL_ID = "voice_call"
        private const val NOTIF_ID = 101
        private const val TAG = "CallService"
    }

    private lateinit var audio: AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        audio = AudioManager().also { it.initialize() }
        WsClient.onAudioReceived = { audio.playAudioData(it) }
        audio.onAudioDataCaptured = { WsClient.sendAudio(it) }

        scope.launch {
            WsClient.call.collect { state ->
                when (state) {
                    is WsClient.Call.InCall -> { Log.i(TAG, "接通，启动音频"); audio.startRecording(); audio.startPlayback() }
                    is WsClient.Call.Ended -> stopSelf()
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                startForeground(NOTIF_ID, notification("正在呼叫 $id"))
                scope.launch { WsClient.ensureConnected(); WsClient.callDevice(id) }
            }
            ACTION_STOP -> { WsClient.endCall(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "通话服务", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("心声 · 语音通话")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        audio.release()
        WsClient.onAudioReceived = null
        scope.cancel()
        super.onDestroy()
    }
}
