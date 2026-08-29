package com.coco.audio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 采集(麦克风→ADPCM) 与 播放(ADPCM→喇叭)。8kHz/单声道/16bit，60ms 一帧。
 */
class AudioManager {
    companion object {
        const val SAMPLE_RATE = 8000
        const val FRAME_BYTES = 960
        private const val TAG = "AudioManager"
        private const val PLAYBACK_GAIN = 1.0f
        private const val PREBUFFER_FRAMES = 2
        private const val MAX_QUEUE = 10
    }

    var onAudioDataCaptured: ((ByteArray) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val playoutReady = AtomicBoolean(false)
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE)
    private val silenceFrame = ByteArray(FRAME_BYTES)

    private val rxBuffer = ByteArray(4096)
    private var rxLen = 0
    private val rxLock = Any()

    private var recordScope: CoroutineScope? = null
    private var playbackScope: CoroutineScope? = null

    fun initialize(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            audioRecord = rec
            val ok = rec.state == AudioRecord.STATE_INITIALIZED
            if (ok) enableAudioEffects(rec.audioSessionId)
            ok
        } catch (e: Exception) {
            Log.e(TAG, "init error: ${e.message}"); false
        }
    }

    /** 启用系统级回声消除(AEC)+噪声抑制(NS)+自动增益(AGC)，抑制外放啸叫/回声 */
    private fun enableAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "AEC 回声消除已启用=${aec?.enabled}")
            } else Log.w(TAG, "本机不支持硬件 AEC")
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "NS 噪声抑制已启用=${ns?.enabled}")
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "AGC 自动增益已启用=${agc?.enabled}")
            }
        } catch (e: Exception) { Log.e(TAG, "启用音效失败: ${e.message}") }
    }

    fun startRecording() {
        val rec = audioRecord ?: return
        if (isRecording.get()) return
        try {
            rec.startRecording()
            isRecording.set(true)
            AdpcmCodec.resetStreamState()
            Log.i(TAG, "开始录音 (8kHz)")
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            recordScope = scope
            scope.launch {
                val buffer = ByteArray(FRAME_BYTES)
                var sent = 0
                while (isRecording.get()) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read >= FRAME_BYTES) {
                        AdpcmCodec.encodeFrame(buffer)?.let {
                            onAudioDataCaptured?.invoke(it)
                            sent++
                            if (sent == 1 || sent % 50 == 0) Log.d(TAG, "已采集编码 $sent 帧")
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read 返回 $read")
                    }
                }
                Log.i(TAG, "录音循环结束, 共 $sent 帧")
            }
        } catch (e: Exception) { Log.e(TAG, "startRecording 异常: ${e.message}"); isRecording.set(false) }
    }

    fun startPlayback() {
        if (isPlaying.get()) return
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 6)
                .build()
            audioTrack = track
            track.play()
            isPlaying.set(true)
            playoutReady.set(false)
            playbackQueue.clear()
            synchronized(rxLock) { rxLen = 0 }
            AdpcmCodec.resetStreamState()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            playbackScope = scope
            scope.launch { playbackLoop() }
            Log.i(TAG, "启动播放, trackBuffer=${minBuf * 6}B")
        } catch (e: Exception) { Log.e(TAG, "startPlayback 异常: ${e.message}") }
    }

    private suspend fun playbackLoop() {
        while (isPlaying.get()) {
            val tickEnd = SystemClock.uptimeMillis() + 60
            if (!playoutReady.get()) {
                if (playbackQueue.size >= PREBUFFER_FRAMES) playoutReady.set(true)
                else { val w = tickEnd - SystemClock.uptimeMillis(); if (w > 0) kotlinx.coroutines.delay(w); continue }
            }
            val frame = playbackQueue.poll(60, TimeUnit.MILLISECONDS)
            writeToTrack(frame ?: silenceFrame)
            val w = tickEnd - SystemClock.uptimeMillis()
            if (w > 0) kotlinx.coroutines.delay(w)
        }
    }

    private var rxPktCount = 0

    /** 收到网络二进制帧，按魔数对齐、解码、入播放队列 */
    fun playAudioData(data: ByteArray) {
        if (!isPlaying.get() || isMuted.get() || data.isEmpty()) return
        val frames = ArrayList<ByteArray>()
        synchronized(rxLock) {
            appendRx(data)
            while (rxLen >= AdpcmCodec.PACKET_BYTES) {
                val head = rxBuffer.copyOfRange(0, AdpcmCodec.PACKET_BYTES)
                if (!AdpcmCodec.isAdpcmPacket(head)) {
                    val magic = findMagic()
                    if (magic <= 0) { if (rxLen <= AdpcmCodec.PACKET_BYTES) break; shiftRx(1) } else shiftRx(magic)
                } else {
                    shiftRx(AdpcmCodec.PACKET_BYTES)
                    AdpcmCodec.decodeToPcm(head)?.let { frames.add(it) }
                }
            }
        }
        for (pcm in frames) if (!playbackQueue.offer(pcm)) { playbackQueue.poll(); playbackQueue.offer(pcm) }
        if (frames.isNotEmpty()) {
            rxPktCount += frames.size
            if (rxPktCount <= frames.size || rxPktCount % 50 == 0) Log.d(TAG, "已解码播放 $rxPktCount 帧, 队列=${playbackQueue.size}")
        }
    }

    private fun appendRx(data: ByteArray) {
        for (b in data) {
            if (rxLen >= rxBuffer.size) shiftRx(1)
            rxBuffer[rxLen++] = b
        }
    }

    private fun shiftRx(count: Int) {
        if (count <= 0 || rxLen <= 0) return
        val drop = minOf(count, rxLen)
        System.arraycopy(rxBuffer, drop, rxBuffer, 0, rxLen - drop)
        rxLen -= drop
    }

    private fun findMagic(): Int {
        val limit = rxLen - 3
        var i = 1
        while (i < limit) {
            if (rxBuffer[i].toInt() == 65 && rxBuffer[i + 1].toInt() == 68 &&
                rxBuffer[i + 2].toInt() == 80 && rxBuffer[i + 3].toInt() == 67
            ) return i
            i++
        }
        return -1
    }

    private fun writeToTrack(data: ByteArray) {
        val track = audioTrack ?: return
        try {
            val shorts = ShortArray(data.size / 2)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            for (i in shorts.indices) {
                var v = (shorts[i] * PLAYBACK_GAIN).toInt()
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                shorts[i] = v.toShort()
            }
            track.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) { Log.e(TAG, "write error: ${e.message}") }
    }

    fun setMute(muted: Boolean) {
        isMuted.set(muted)
        if (muted) {
            try { audioTrack?.flush(); playbackQueue.clear(); playoutReady.set(false); synchronized(rxLock) { rxLen = 0 } } catch (_: Exception) {}
        }
    }

    fun stopRecording() {
        if (isRecording.getAndSet(false)) Log.i(TAG, "停止录音")
        recordScope?.cancel(); recordScope = null
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    fun stopPlayback() {
        if (isPlaying.getAndSet(false)) Log.i(TAG, "停止播放")
        playoutReady.set(false)
        playbackScope?.cancel(); playbackScope = null
        playbackQueue.clear()
        rxPktCount = 0
        synchronized(rxLock) { rxLen = 0 }
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    fun release() {
        Log.i(TAG, "释放音频资源")
        stopRecording(); stopPlayback()
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
