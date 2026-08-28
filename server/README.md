# 心声 服务器

App ↔ 服务器 ↔ 设备(ESP32) 的 WebSocket 信令/语音转发服务器。

## 部署（Docker）

```bash
cd /opt/audio          # 服务器上的部署目录
docker build -t xinsheng-server .
# echo 模式（单端自测）
docker run -d --name xinsheng --restart unless-stopped -p 40000:8080 -e MODE=echo -e PORT=8080 xinsheng-server
# relay 模式（App 与硬件真实对讲）
docker rm -f xinsheng && docker run -d --name xinsheng --restart unless-stopped -p 40000:8080 -e MODE=relay -e PORT=8080 xinsheng-server
```

- 对外地址：`ws://<服务器IP>:40000`
- 当前联调服务器：`ws://8.162.5.160:40000`
- 查看日志：`docker logs -f xinsheng`

## 协议
见仓库 `docs/硬件对接文档.html`（第 5 章）。

- App→服务器：`connect_app / ping / check_device_status / call_request / call_end / wifi_scan / wifi_config / set_volume / factory_reset / switch_network / pairing_gpio`
- 服务器→App：`device_status / device_online / device_offline / call_connected / call_ended / call_result / wifi_list / wifi_test_result`
- 设备→服务器：`connect_device / ping / call_accept`
- 服务器→设备：`incoming_call / call_ended / 管理指令透传`
- 语音：二进制帧，251 字节 IMA-ADPCM 包（8kHz/单声道/16bit）
