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
import kotlinx.coroutines.delay
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

        /** 当前运行中的服务实例，供通话页面的静音/免提按钮调用 */
        @Volatile var instance: CallService? = null
            private set
        /** 免提默认开启：对讲机场景手机一般不贴耳朵 */
        const val DEFAULT_SPEAKER_ON = true
    }

    private lateinit var audio: AudioManager
    private lateinit var sysAudio: android.media.AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioStarted = false

    /** 静音：停止把麦克风内容发给对方（仍发静音帧保持节拍） */
    fun setMicMuted(muted: Boolean) { audio.micMuted = muted; Log.i(TAG, "麦克风静音=$muted") }

    /** 免提：true 走手机外放扬声器，false 走听筒 */
    fun setSpeakerOn(on: Boolean) {
        sysAudio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        sysAudio.isSpeakerphoneOn = on
        Log.i(TAG, "免提=$on")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "onCreate 服务创建")
        sysAudio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        audio = AudioManager().also { val ok = it.initialize(); Log.i(TAG, "音频初始化 result=$ok") }
        WsClient.onAudioReceived = { audio.playAudioData(it) }
        audio.onAudioDataCaptured = { WsClient.sendAudio(it) }

        // 关键修复：清掉上一通残留的通话状态，否则 StateFlow 会把上次的 Ended 立刻重放，导致新服务被立即结束
        WsClient.resetCall()

        scope.launch {
            WsClient.call.collect { state ->
                Log.i(TAG, "通话状态变化: $state (audioStarted=$audioStarted)")
                when (state) {
                    is WsClient.Call.InCall -> if (!audioStarted) {
                        audioStarted = true
                        Log.i(TAG, "通话接通，启动录音+播放")
                        setSpeakerOn(DEFAULT_SPEAKER_ON)
                        audio.startRecording(); audio.startPlayback()
                    }
                    is WsClient.Call.Ended -> if (audioStarted) {
                        Log.i(TAG, "通话结束，停止服务")
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                startForeground(NOTIF_ID, notification("正在呼叫 $id"))
                scope.launch {
                    WsClient.ensureConnected()
                    var tries = 0
                    while (!WsClient.isOnline() && tries < 50) { delay(100); tries++ }
                    Log.i(TAG, "准备呼叫 $id, 已连接=${WsClient.isOnline()} (等待 ${tries * 100}ms)")
                    WsClient.callDevice(id)
                }
            }
            ACTION_STOP -> { Log.i(TAG, "用户挂断"); WsClient.endCall(); stopSelf() }
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
        Log.i(TAG, "onDestroy 服务销毁，释放音频")
        instance = null
        audio.release()
        try {
            @Suppress("DEPRECATION")
            sysAudio.isSpeakerphoneOn = false
            sysAudio.mode = android.media.AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        WsClient.onAudioReceived = null
        scope.cancel()
        super.onDestroy()
    }
}
