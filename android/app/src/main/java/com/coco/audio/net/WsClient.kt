package com.coco.audio.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 心声 WebSocket 客户端（单例）。连接心声服务器，收发 JSON 信令与二进制语音。
 */
object WsClient {
    const val SERVER_URL = "ws://8.162.5.160:40000"
    private const val TAG = "WsClient"

    sealed class Conn { object Disconnected : Conn(); object Connecting : Conn(); object Connected : Conn() }
    sealed class Call { object Idle : Call(); object Calling : Call(); object InCall : Call(); data class Ended(val reason: String) : Call() }

    private val _conn = MutableStateFlow<Conn>(Conn.Disconnected)
    val conn: StateFlow<Conn> = _conn
    private val _call = MutableStateFlow<Call>(Call.Idle)
    val call: StateFlow<Call> = _call
    private val _deviceOnline = MutableStateFlow<Pair<String, Boolean>?>(null)
    val deviceOnline: StateFlow<Pair<String, Boolean>?> = _deviceOnline

    var onAudioReceived: ((ByteArray) -> Unit)? = null

    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val userClosed = AtomicBoolean(false)
    private var activeDeviceId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isOnline() = _conn.value is Conn.Connected
    fun isInCall() = _call.value is Call.Calling || _call.value is Call.InCall

    fun connect() {
        userClosed.set(false)
        if (isOnline()) return
        _conn.value = Conn.Connecting
        ws?.cancel()
        ws = client.newWebSocket(Request.Builder().url(SERVER_URL).build(), listener)
    }

    fun ensureConnected() { if (!isOnline()) connect() }

    private fun sendJson(build: JSONObject.() -> Unit): Boolean {
        val w = ws ?: return false
        return w.send(JSONObject().apply(build).toString())
    }

    fun checkDeviceStatus(id: String) { if (id.isEmpty()) return; sendJson { put("type", "check_device_status"); put("device_id", id) } }
    fun callDevice(id: String) {
        if (sendJson { put("type", "call_request"); put("device_id", id) }) {
            activeDeviceId = id; _call.value = Call.Calling
        }
    }
    fun endCall() { sendJson { put("type", "call_end") }; activeDeviceId = null; _call.value = Call.Idle }
    fun requestWifiScan(id: String) { sendJson { put("type", "wifi_scan"); put("device_id", id) } }
    fun sendWifiConfig(id: String, ssid: String, pass: String) { sendJson { put("type", "wifi_config"); put("device_id", id); put("ssid", ssid); put("password", pass) } }
    fun sendVolume(id: String, v: Int) { sendJson { put("type", "set_volume"); put("device_id", id); put("volume", v) } }
    fun sendFactoryReset(id: String) { sendJson { put("type", "factory_reset"); put("device_id", id) } }
    fun sendSwitchNetwork(id: String, mode: String) { sendJson { put("type", "switch_network"); put("device_id", id); put("mode", mode) } }
    fun sendPairingGpio(id: String, level: Int) { sendJson { put("type", "pairing_gpio"); put("device_id", id); put("level", level) } }

    fun sendAudio(data: ByteArray) { ws?.takeIf { isOnline() }?.send(data.toByteString(0, data.size)) }

    fun disconnect() { userClosed.set(true); ws?.close(1000, "quit"); ws = null; _conn.value = Conn.Disconnected }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _conn.value = Conn.Connected
            webSocket.send(JSONObject().put("type", "connect_app").toString())
            Log.i(TAG, "connected")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = try { JSONObject(text) } catch (e: Exception) { return }
            when (json.optString("type")) {
                "device_status" -> _deviceOnline.value = json.optString("device_id") to json.optBoolean("online")
                "device_online" -> _deviceOnline.value = json.optString("device_id") to true
                "device_offline" -> _deviceOnline.value = json.optString("device_id") to false
                "call_connected" -> _call.value = Call.InCall
                "call_ended" -> _call.value = Call.Ended("已结束")
                "call_result" -> if (!json.optBoolean("success", true)) { _call.value = Call.Ended(json.optString("error", "呼叫失败")) }
            }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { onAudioReceived?.invoke(bytes.toByteArray()) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { handleClose() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { Log.w(TAG, "fail: ${t.message}"); handleClose() }
    }

    private fun handleClose() {
        ws = null
        if (userClosed.get()) { _conn.value = Conn.Disconnected }
        else { _conn.value = Conn.Disconnected /* 简化：交由 UI 触发重连 */ }
    }
}
