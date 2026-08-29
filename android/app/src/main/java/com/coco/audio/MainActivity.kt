package com.coco.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.coco.audio.net.WsClient
import com.coco.audio.service.CallService
import org.json.JSONObject

// 方案A（iOS 风）配色
private val Bg = Color(0xFFF2F2F7)
private val Green = Color(0xFF34C759)
private val Red = Color(0xFFFF3B30)
private val Blue = Color(0xFF007AFF)
private val KeyFill = Color(0x1F787880)
private val Ink = Color(0xFF1C1C1E)
private val Sub = Color(0xFF8E8E93)

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WsClient.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { App() } }
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) WsClient.connect() else permLauncher.launch(missing.toTypedArray())
    }
}

private fun startCall(ctx: Context, id: String) {
    val i = Intent(ctx, CallService::class.java).setAction(CallService.ACTION_START).putExtra(CallService.EXTRA_DEVICE_ID, id)
    ContextCompat.startForegroundService(ctx, i)
}
private fun stopCall(ctx: Context) {
    ctx.startService(Intent(ctx, CallService::class.java).setAction(CallService.ACTION_STOP))
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val call by WsClient.call.collectAsState()
    val presence by WsClient.deviceOnline.collectAsState()
    val wifiList by WsClient.wifiList.collectAsState()
    var deviceId by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var pwdSsid by remember { mutableStateOf<String?>(null) }

    fun needId(): Boolean {
        if (deviceId.length >= 6) return true
        Toast.makeText(ctx, "请先输入 6 位设备 ID", Toast.LENGTH_SHORT).show()
        return false
    }

    // 输入满 6 位时查询在线
    LaunchedEffect(deviceId) { if (deviceId.length >= 6) { WsClient.ensureConnected(); WsClient.checkDeviceStatus(deviceId) } }

    val inCall = call is WsClient.Call.Calling || call is WsClient.Call.InCall
    val online = presence?.let { it.first == deviceId && it.second } ?: false

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (inCall) {
            InCallScreen(deviceId, call is WsClient.Call.InCall, onEnd = { stopCall(ctx) })
        } else {
            DialerScreen(
                deviceId = deviceId,
                online = online,
                onDigit = { if (deviceId.length < 10) deviceId += it },
                onDelete = { if (deviceId.isNotEmpty()) deviceId = deviceId.dropLast(1) },
                onCall = { if (needId()) startCall(ctx, deviceId) },
                onSettings = { if (needId()) showSettings = true },
                onWifi = { if (needId()) { WsClient.requestWifiScan(deviceId); Toast.makeText(ctx, "正在请求设备扫描 WiFi…", Toast.LENGTH_SHORT).show() } }
            )
        }
    }

    if (showSettings) SettingsSheet(deviceId, onDismiss = { showSettings = false })

    // WiFi 扫描结果
    if (wifiList.isNotEmpty() && pwdSsid == null) {
        AlertDialog(
            onDismissRequest = { WsClient.clearWifiList() },
            title = { Text("选择要配网的 WiFi") },
            text = {
                Column {
                    wifiList.forEach { item ->
                        val ssid = item.optString("ssid")
                        Row(
                            Modifier.fillMaxWidth().clickable { pwdSsid = ssid }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(ssid, color = Ink)
                            Text("${item.optInt("rssi")} dBm", color = Sub, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { WsClient.clearWifiList() }) { Text("取消") } }
        )
    }

    // WiFi 密码输入
    pwdSsid?.let { ssid ->
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwdSsid = null },
            title = { Text("连接至 $ssid") },
            text = { OutlinedTextField(value = pwd, onValueChange = { pwd = it }, label = { Text("WiFi 密码") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    WsClient.sendWifiConfig(deviceId, ssid, pwd)
                    Toast.makeText(ctx, "配网指令已发送", Toast.LENGTH_LONG).show()
                    pwdSsid = null; WsClient.clearWifiList()
                }) { Text("发送并测试") }
            },
            dismissButton = { TextButton(onClick = { pwdSsid = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun DialerScreen(
    deviceId: String, online: Boolean,
    onDigit: (String) -> Unit, onDelete: () -> Unit, onCall: () -> Unit,
    onSettings: () -> Unit, onWifi: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(top = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon("⚙") { onSettings() }
            Spacer(Modifier.weight(1f))
            PresencePill(online)
            Spacer(Modifier.weight(1f))
            CircleIcon("📶") { onWifi() }
        }
        Spacer(Modifier.height(6.dp))
        Text("心声", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Blue)
        Text("远程语音对讲", fontSize = 12.sp, color = Sub)
        Spacer(Modifier.height(18.dp))
        // 号码
        Text(
            if (deviceId.isEmpty()) "输入设备 ID" else deviceId,
            fontSize = if (deviceId.isEmpty()) 22.sp else 40.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (deviceId.isEmpty()) Sub else Ink
        )
        Spacer(Modifier.height(20.dp))
        // 拨号盘
        val rows = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { (d, l) -> DialKey(d, l) { onDigit(d) } }
            }
        }
        Spacer(Modifier.weight(1f))
        // 呼叫 + 退格
        Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(74.dp).clip(CircleShape).background(Green).clickable { onCall() },
                contentAlignment = Alignment.Center
            ) { Text("📞", fontSize = 30.sp) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("⌫", fontSize = 24.sp, color = Ink, modifier = Modifier.clickable { onDelete() })
            }
        }
    }
}

@Composable
private fun DialKey(digit: String, letters: String, onClick: () -> Unit) {
    Box(
        Modifier.size(74.dp).clip(CircleShape).background(KeyFill).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit, fontSize = 30.sp, fontWeight = FontWeight.Normal, color = Ink)
            if (letters.isNotEmpty()) Text(letters, fontSize = 9.sp, color = Sub)
        }
    }
}

@Composable
private fun CircleIcon(glyph: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 18.sp)
    }
}

@Composable
private fun PresencePill(online: Boolean) {
    val c = if (online) Green else Red
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(8.dp))
        Text(if (online) "设备在线" else "不在线", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c)
    }
}

@Composable
private fun InCallScreen(deviceId: String, connected: Boolean, onEnd: () -> Unit) {
    var mic by remember { mutableStateOf(false) }
    var spk by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF1C1C1E)).padding(top = 96.dp, bottom = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(deviceId, fontSize = 34.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Spacer(Modifier.height(10.dp))
        Text(if (connected) "通话中" else "正在呼叫…", fontSize = 16.sp, color = Color(0x99EBEBF5))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            CallCtrl("🎤", "静音", mic) { mic = !mic }
            CallCtrl("🔊", "免提", spk) { spk = !spk }
        }
        Spacer(Modifier.height(30.dp))
        Box(
            Modifier.size(74.dp).clip(CircleShape).background(Red).clickable { onEnd() },
            contentAlignment = Alignment.Center
        ) { Text("📵", fontSize = 30.sp) }
    }
}

@Composable
private fun CallCtrl(glyph: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(if (active) Color.White else Color(0x33FFFFFF)).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(glyph, fontSize = 24.sp) }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.sp, color = Color(0xD9EBEBF5))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(deviceId: String, onDismiss: () -> Unit) {
    var volume by remember { mutableStateOf(80f) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg) {
        Column(Modifier.padding(24.dp).padding(bottom = 24.dp)) {
            Text("设备设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("设备 ID：$deviceId", fontSize = 12.sp, color = Sub)
            Spacer(Modifier.height(18.dp))

            Text("咪头音量  ${volume.toInt()}%", fontSize = 14.sp, color = Sub)
            Slider(
                value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, steps = 19,
                onValueChangeFinished = { WsClient.sendVolume(deviceId, volume.toInt()) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Blue)
            )
            Spacer(Modifier.height(14.dp))

            Text("联网方式", fontSize = 14.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { WsClient.sendSwitchNetwork(deviceId, "wifi") }, modifier = Modifier.weight(1f)) { Text("切换 Wi-Fi") }
                FilledTonalButton(onClick = { WsClient.sendSwitchNetwork(deviceId, "4g") }, modifier = Modifier.weight(1f)) { Text("切换 4G") }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { WsClient.sendPairingGpio(deviceId, 0) }, modifier = Modifier.fillMaxWidth()) { Text("配对（点按）") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { WsClient.sendFactoryReset(deviceId) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.12f), contentColor = Red)
            ) { Text("复位（恢复出厂）") }
        }
    }
}
