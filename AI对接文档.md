# 心声 · AI 对接文档

本文件面向两类读者：
1. **AI/开发者**：快速理解整个系统，便于继续开发或接入。
2. **AI 语音接入**：把一个 AI 语音机器人（ASR+LLM+TTS）作为"通话端"接进来，实现"打电话给 AI / AI 应答设备来电"。

---

## 一、系统是什么

心声是一套 **基于 WebSocket 的实时语音对讲系统**：

```
心声 App(安卓/iOS)  ⇄  云服务器(转发)  ⇄  硬件设备(ESP32) / AI 语音端
        主叫/管理端         8.162.5.160:40000        被叫/终端
```

- App 与设备**不直接通信**，全部经服务器按 6 位设备 ID 转发。
- 一条 WebSocket 里同时跑两种数据：**JSON 文本信令** 与 **二进制语音帧**。
- 语音编码：**IMA-ADPCM，8kHz/单声道/16bit**，每帧 60ms、251 字节。

联调服务器（已部署，Docker，开机自启）：`ws://8.162.5.160:40000`
管理后台：`http://8.162.5.160:40000/`（账号 `admin` / 密码 `xinsheng2026`）

---

## 二、仓库结构

```
cocoAudio/
├── server/           转发服务器 + 管理API + 会话日志 (Node + ws)
│   ├── server.js
│   ├── Dockerfile
│   └── package.json
├── admin/            管理后台 (Vue3 + Element Plus + vite)
│   └── src/{api,stores,router,layouts,views}
├── android/          Android 客户端 (Kotlin + Compose)，包名 com.coco.audio
│   └── app/src/main/java/com/coco/audio/{audio,net,service}
├── Audio/            iOS 客户端 (SwiftUI)
│   └── Audio/{AdpcmCodec,WSClient,AudioEngine,CallController,ContentView}.swift
├── docs/             硬件对接文档.html / 方案A界面 / 系统讲解图 / 图标
├── 硬件对接文档.html  (docs 同款，给硬件客户)
└── AI对接文档.md      本文件
```

---

## 三、通信协议（完整）

### 3.1 App ↔ 服务器（文本 JSON）

App → 服务器：
| type | 字段 | 说明 |
|---|---|---|
| `connect_app` | — | 上线自报身份 |
| `ping` | — | 心跳（每 15s） |
| `check_device_status` | `device_id` | 查设备是否在线 |
| `call_request` | `device_id` | 发起呼叫 |
| `call_end` | — | 挂断 |
| `wifi_scan` | `device_id` | 让设备扫描 WiFi |
| `wifi_config` | `device_id,ssid,password` | 下发 WiFi 配置 |
| `set_volume` | `device_id,volume`(40/60/80/100) | 麦克风音量 |
| `factory_reset` | `device_id` | 恢复出厂 |
| `switch_network` | `device_id,mode`(wifi/4g) | 切换联网 |
| `pairing_gpio` | `device_id,level`(0/1) | 配对按键 |

服务器 → App：
| type | 字段 | 说明 |
|---|---|---|
| `device_status`/`device_online`/`device_offline` | `device_id,online` | 在线状态 |
| `call_connected` | — | 已接通，App 开始推流 |
| `call_ended` | — | 结束 |
| `call_result` | `success,error` | 呼叫失败原因 |
| `wifi_list` | `data:[{ssid,rssi}]` | 扫描结果 |
| `wifi_test_result` | `success` | 配网结果 |

### 3.2 设备 ↔ 服务器（文本 JSON）

设备（ESP32 或 AI 端）需实现：
| 方向 | 报文 | 说明 |
|---|---|---|
| 设备→服务器 | `{"type":"connect_device","device_id":"123456"}` | 上线注册 |
| 设备→服务器 | `{"type":"ping"}` | 心跳保活 |
| 服务器→设备 | `{"type":"incoming_call"}` | 有 App 呼叫本机 |
| 设备→服务器 | `{"type":"call_accept"}` | 接听（服务器随即通知 App `call_connected`） |
| 服务器→设备 | `{"type":"call_ended"}` | 通话结束 |
| 服务器→设备 | `wifi_scan/wifi_config/set_volume/...` | 管理指令（relay 模式透传） |

### 3.3 语音（二进制帧）

- 每个 WebSocket 二进制消息 = 1 个 **251 字节** IMA-ADPCM 包。
- 包结构：

| 偏移 | 长度 | 内容 |
|---|---|---|
| 0–3 | 4 | 魔数 `"ADPC"` = `41 44 50 43` |
| 4 | 1 | 版本 `0x01` |
| 5–6 | 2 | 采样数 480（小端：`E0 01`） |
| 7–8 | 2 | 首个 PCM 采样（int16 小端，预测器初值） |
| 9 | 1 | 步长索引初值 (0–88) |
| 10 | 1 | 保留 `0x00` |
| 11–250 | 240 | 4-bit ADPCM 数据（479 采样，低 nibble 在前） |

- PCM：8000Hz / 单声道 / 16bit LE，480 采样 = 960 字节 = 60ms。
- 参考实现：`android/.../audio/AdpcmCodec.kt`、`Audio/Audio/AdpcmCodec.swift`、`server` 无需编解码（纯转发）。

---

## 四、把 AI 语音机器人接入（作为一个"设备端"）

目标：App 呼叫某个设备 ID，由 AI 接听并对话（AI 听到用户语音 → ASR → LLM → TTS → 回传语音）。

### 4.1 接入流程

1. 用 WebSocket 连接 `ws://8.162.5.160:40000`。
2. 发送注册：`{"type":"connect_device","device_id":"900001"}`（选一个 AI 专属 ID）。
3. 收到 `{"type":"incoming_call"}` → 回 `{"type":"call_accept"}`。
4. 持续接收二进制帧：每 251 字节 → ADPCM 解码为 8kHz PCM → 送 ASR。
5. LLM 生成回复 → TTS 合成 8kHz 单声道 PCM → 按 480 采样/帧 ADPCM 编码 → 逐帧二进制发回。
6. 结束：收到/发送 `call_ended`。
7. 服务器需处于 **relay 模式**（管理后台或 `POST /api/mode {"mode":"relay"}` 切换）。

### 4.2 Node.js 参考骨架（AI 端）

```js
const WebSocket = require('ws');
const ws = new WebSocket('ws://8.162.5.160:40000');
const AI_ID = '900001';

ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'connect_device', device_id: AI_ID }));
  setInterval(() => ws.readyState === 1 && ws.send(JSON.stringify({ type: 'ping' })), 15000);
});

ws.on('message', (data, isBinary) => {
  if (isBinary) {
    // data 为 251 字节 ADPCM 包 -> 解码 8kHz PCM -> 送 ASR（流式）
    const pcm = adpcmDecode(data);   // 见 AdpcmCodec 的解码逻辑
    asr.feed(pcm);
    return;
  }
  const msg = JSON.parse(data.toString());
  if (msg.type === 'incoming_call') ws.send(JSON.stringify({ type: 'call_accept' }));
  if (msg.type === 'call_ended') asr.reset();
});

// TTS 产出 8kHz/mono/16bit PCM 后，按 480 采样一帧编码发送：
function speak(pcm16le /* Buffer, 8kHz */) {
  for (let off = 0; off + 960 <= pcm16le.length; off += 960) {
    const frame = pcm16le.subarray(off, off + 960);
    ws.send(adpcmEncode(frame));   // 返回 251 字节包；编码器需保持帧间步长索引连续
  }
}
```

> ADPCM 编解码可直接移植仓库里的 `AdpcmCodec`（Kotlin/Swift 版逻辑完全一致，标准 IMA/DVI 4-bit，步长索引跨帧连续、通话开始复位为 0）。

### 4.3 反向：让 App 端接 AI（可选）

App 无需改动。只要 AI 以某个 `device_id` 注册在线，App 输入该 ID 呼叫即可与 AI 通话。

---

## 五、各部分如何运行

### 服务器
```bash
cd server && npm install
MODE=relay PORT=40000 node server.js       # 或用 Docker，见 server/README.md
```

### 管理后台
```bash
cd admin && npm install && npm run dev      # 本地 http://localhost:3000（已代理到线上服务器）
# 或 npm run build 后由服务器托管：http://8.162.5.160:40000/
```

### Android
用 Android Studio 打开 `android/`，Sync 后运行。服务器地址在 `net/WsClient.kt` 的 `SERVER_URL`。

### iOS
用 Xcode 打开 `Audio/Audio.xcodeproj`，真机运行（需麦克风权限，已配）。服务器地址在 `WSClient.swift` 的 `serverURL`。

---

## 六、测试方法（重要）

服务器有两种模式：

- **echo（回环自测，默认）**：`check_device_status` 恒返回在线；呼叫任意 6 位 ID 立即接通，语音**原样回传**。用来单独验证一端（App/AI/设备）的音频通道——对着说话能听到自己 = 通。
  > 这解释了"随便输 6 位 ID 都显示在线"——是 echo 模式的预期行为，不是 bug。
- **relay（中转对讲）**：`check_device_status` 只有设备真的注册了才在线；App 与设备之间转发语音。**接真硬件或 AI 端时用这个。**

切换：管理后台仪表盘一键切换，或 `POST http://8.162.5.160:40000/api/mode {"mode":"relay"}`（需登录 token）。

联调建议：
1. echo 模式：App 单独跑通"呼叫→听到自己"。
2. relay 模式 + 一个设备端（`server` 目录旁的 `fake-device.js` 或真 ESP32 或 AI 端）注册同一 ID。
3. App 呼叫该 ID → 双向通话。
4. 出问题看管理后台"会话记录"：每条连接的信令流 + 语音帧数 + **ADPCM 格式校验**（`audioBad>0` 说明对端语音包格式不对）。

---

## 七、管理后台 API（需登录 token，请求头 `x-token`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/login` | `{user,pass}` → `{ok,token}` |
| GET | `/api/stats` | 概览 + 在线设备 |
| GET | `/api/sessions` | 会话列表 |
| GET | `/api/session?id=` | 会话详情（事件流） |
| GET | `/api/logs` | 实时日志 |
| POST | `/api/mode` | `{mode:"echo"\|"relay"}` |
| POST | `/api/command` | `{device_id,type,...}` 向设备下发指令 |
| POST | `/api/kick` | `{device_id}` 断开设备 |
| WS | `/admin?token=` | 实时推送 hello/log/event |

---

## 八、已知事项 / 注意

- **无回声消除**：App(安卓)未做 AEC；iOS 用 `voiceChat` 模式系统自带 AEC。硬件外放需自行做 AEC 或用听筒，否则啸叫。
- **明文 ws + 固定口令**：仅供内部联调，正式上线需加 HTTPS/WSS 与更强鉴权。
- **4G**：ESP32-A1S 仅 WiFi，`switch_network:4g` 需外挂 4G 模组。
- 服务器为单进程内存态转发，重启后在线状态清空（会话日志已落盘 `/opt/audio/logs`）。
