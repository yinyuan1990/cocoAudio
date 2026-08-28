# 心声 管理后台（Vue 3 + Vite）

对接心声服务器的管理 API（`/api/*`）与实时事件（`/admin` WebSocket）。

## 功能（基础版）
- 概览：在线设备数、App 连接数、进行中通话数、运行时长
- 服务器模式一键切换：echo / relay
- 在线设备列表：在线时长、通话状态
- 对设备下发指令：WiFi 扫描、音量、切 WiFi/4G、复位、踢下线
- 实时事件日志

## 运行
```bash
cd admin
npm install
npm run dev        # 打开 http://localhost:5173
```
顶部输入服务器地址（默认 `http://8.162.5.160:40000`）后点“连接”。

## 构建部署
```bash
npm run build      # 产物在 dist/，可用任意静态服务器托管
```

## 依赖的后端接口
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/stats` | 概览 + 设备列表 |
| GET | `/api/logs` | 最近事件日志 |
| POST | `/api/mode` | `{mode:"echo"\|"relay"}` 切换模式 |
| POST | `/api/command` | `{device_id,type,...}` 向设备下发指令 |
| POST | `/api/kick` | `{device_id}` 断开设备 |
| WS | `/admin` | 实时推送 hello/log/event |
