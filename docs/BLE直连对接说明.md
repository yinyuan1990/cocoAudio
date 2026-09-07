# 心声 · 手机 App 与设备 BLE 直连对接说明

> 适用：固件 v16 起。目标：手机与板子在没有网络时通过低功耗蓝牙（BLE）双向对讲。
> 原则：**BLE 只是把 WebSocket 换成了蓝牙当传输线**，信令 JSON 与语音帧格式和走服务器时完全一致，App 端编解码/放音/录音代码全部复用，只需新增一个"BLE 传输层"。

---

## 1. 发现设备

- 设备空闲时持续广播，**广播名** `XS-<6位设备号>`，例如 `XS-123456`。
- 广播包带服务 UUID（见下），iOS 可按服务 UUID 扫描，Android 可按名字前缀 `XS-` 或服务 UUID 扫描。
- 不需要在手机系统设置里配对/绑定，App 内直接 connect。
- 板子同时只接受 1 个手机连接；连上后停止广播，断开后自动恢复广播。
- **安全约定（v1）**：App 只允许连接用户已在 App 内添加过的设备号；`call_request` 必须带 `device_id`，与板子不一致则拒绝。后续可加配对码。

## 2. GATT 服务

| 项 | UUID | 属性 | 用途 |
|---|---|---|---|
| Service | `58530000-5853-4341-4c4c-000000000001` | — | 心声直连服务 |
| `ctrl` | `58530000-5853-4341-4c4c-000000000002` | Write, Write Without Response, Notify | JSON 信令，双向 |
| `audio_in` | `58530000-5853-4341-4c4c-000000000003` | Write Without Response | 语音：手机 → 板子 |
| `audio_out` | `58530000-5853-4341-4c4c-000000000004` | Notify | 语音：板子 → 手机 |

连接后 App 必须：
1. 请求最大 MTU（Android `requestMtu(517)`；iOS 系统自动协商，通常 185）。
2. 订阅 `ctrl` 与 `audio_out` 的 Notify（写 CCCD）。
3. 订阅完成后板子会主动推一条 `device_info`（见 §4），收到即表示链路就绪。

单包最大有效载荷 = **MTU − 3** 字节（iOS 185 → 182；Android 517 → 514）。

## 3. 分包规则

### 3.1 `ctrl`（JSON 文本）
- 每条消息 = UTF-8 JSON 文本 + 结尾 `\n`（0x0A）。
- 一条消息若超过单包载荷，拆成多个包连续写/通知；**接收端按 `\n` 切分、拼接**，不依赖包边界。
- 单条消息不超过 512 字节。

### 3.2 `audio_in` / `audio_out`（语音帧）
- 一帧 = 与 WebSocket 完全相同的 **251 字节 IMA-ADPCM 包**（`ADPC` 魔数、8kHz、480 采样 = 60ms）。
- 每个 BLE 包 = **1 字节头 + 载荷**：

```
bit7..3  seq   帧序号 0~31 循环（同一帧的所有分片 seq 相同）
bit2     last  1 = 本帧最后一片
bit1..0  idx   分片序号 0~3
```

- 分片载荷长度 = 单包载荷 − 1（iOS 181，Android 最多 513）。251 字节帧在 iOS 拆 2 片（181 + 70），在 Android 1 片即可。
- 接收端：`idx==0` 时以该 seq 开新帧缓冲；后续分片 seq 必须相同且 idx 连续，否则丢弃整帧；`last==1` 且累计长度 == 251 时交给 ADPCM 解码器。
- 发送节奏与 WS 一致：每 60ms 一帧。丢帧不重传，接收端按现有抖动缓冲处理。
- 语音帧只在通话态（收到 `call_connected` 之后、`call_ended` 之前）收发；通话外收到的帧板子直接丢弃。

## 4. 信令（ctrl 通道，JSON）

字段名与走服务器时完全一致，服务器相关字段（如 `device_id`）照常带上。

**板子 → App**

| type | 字段 | 何时 |
|---|---|---|
| `device_info` | `device_id, fw, state, net, rssi` | 连接就绪时主动推；收到 `get_state` 时回 |
| `call_connected` | — | 接受了 `call_request`，此刻起双向收发语音 |
| `call_result` | `success:false, error` | 拒绝呼叫（`error` 以 `busy` 开头 = 板子正在别的通话） |
| `call_ended` | — | 通话结束（App 主动挂断的回执，或板子侧结束） |
| `wifi_list` | `data:[{ssid,rssi}]` | 回应 `wifi_scan` |
| `wifi_test_result` | `success` | 回应 `wifi_config`（成功后板子会重启，BLE 会断开） |

`state` 取值：`idle` 空闲 / `ws_call` 网络通话中 / `ble_call` 蓝牙通话中 / `cell_call` 电话卡通话中。
`net` 取值：`wifi` / `4g` / `none`（当前联网方式）。

**App → 板子**

| type | 字段 | 说明 |
|---|---|---|
| `get_state` | — | 主动查一次 `device_info` |
| `call_request` | `device_id` | 发起蓝牙对讲；板子空闲则回 `call_connected`，否则回 `call_result busy` |
| `call_end` | — | 挂断，板子回 `call_ended` |
| `set_volume` | `volume` 0~100 | 设备咪头音量（同 WS） |
| `set_speaker_volume` | `volume` 0~100 | 设备喇叭音量（同 WS） |
| `wifi_scan` | — | 让板子扫 WiFi（同 WS；顺带可当 BLE 配网用） |
| `wifi_config` | `ssid, password` | 下发 WiFi（同 WS） |
| `switch_network` | `mode`: `wifi` / `4g` / `auto` | 网络模式，存芯片，重启网络生效 |
| `factory_reset` | — | 恢复出厂 |
| `set_log` | `module`(sys/net/ws/audio/ble/cmd/all), `level` 0~3, `remote` bool, `ble_stats` bool（均可选） | 调板子日志级别 / 开关日志上送服务器 / 让板子打一行蓝牙统计；板子回 `log_config {remote, levels}` |

## 5. 一次蓝牙对讲的完整流程

```
App                                   板子
 |-- BLE connect ---------------------->|
 |-- requestMtu / 订阅 ctrl,audio_out -->|
 |<-- ctrl: device_info{state:idle} ----|
 |-- ctrl: call_request{device_id} ---->|   板子空闲 → 进入 ble_call，喇叭功放打开
 |<-- ctrl: call_connected -------------|   （同时板子向服务器上报忙，别人 App 打不进）
 |<== audio_out: 251B 帧 每 60ms =======|
 |== audio_in:  251B 帧 每 60ms =======>|
 |-- ctrl: call_end ------------------->|
 |<-- ctrl: call_ended -----------------|   板子回空闲，向服务器解除忙
 |-- BLE disconnect ------------------->|   （不断开也可以，板子保持连接，可再次 call_request）
```

异常：
- 通话中 BLE 断开 → 板子自动结束通话、恢复广播；App 侧按挂断处理。
- 板子正在网络通话/电话卡通话时收到 `call_request` → `call_result {success:false, error:"busy ..."}`，App 提示"设备正在通话中"。
- 蓝牙对讲中，服务器那边有人呼叫该设备 → 服务器直接回对方忙线，不影响蓝牙通话。

## 6. App 端实现建议

- 抽一个 `Transport` 接口：`sendJson(obj)`、`sendAudio(bytes)`、回调 `onJson(obj)` / `onAudio(bytes)`；现有 `WsClient` 与新 `BleClient` 各实现一份，通话页/编解码/AudioRecord/AudioTrack 不感知走的是哪条线。
- Android：`BluetoothGatt`，`requestMtu(517)`，`audio_in` 用 `WRITE_TYPE_NO_RESPONSE`，注意 Android 13+ 需要 `BLUETOOTH_SCAN/CONNECT` 运行时权限，扫描用 `ScanFilter` 按 service UUID。
- iOS：`CoreBluetooth`，扫描按 service UUID（后台也可），`audio_in` 用 `.withoutResponse` 并在 `canSendWriteWithoutResponse` 为 true 时再写，`maximumWriteValueLength(for: .withoutResponse)` 就是单包载荷。
- 蓝牙对讲时手机与板子多半在同一房间，**默认用听筒而不是免提**，避免啸叫（板子侧回声抑制仍然生效）。
- 设备卡片：扫到 `XS-<设备号>` 且该设备已添加 → 显示"蓝牙在附近"；设备网络离线但蓝牙在附近 → 点亮"蓝牙直连"按钮并提示。
