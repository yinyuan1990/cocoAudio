import Foundation

/// 心声 WebSocket 客户端。收发 JSON 信令与二进制语音。
final class WSClient: NSObject, ObservableObject {
    static let shared = WSClient()
    static let serverURL = "ws://8.162.5.160:40000"

    enum CallState: Equatable { case idle, calling, inCall, ended(String) }

    @Published var connected = false
    @Published var callState: CallState = .idle
    @Published var deviceOnline: (id: String, online: Bool)? = nil

    var onAudioReceived: ((Data) -> Void)?

    private var task: URLSessionWebSocketTask?
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    private var activeDeviceId: String?
    private var pingTimer: Timer?

    var isOnline: Bool { connected }
    var isInCall: Bool { callState == .calling || callState == .inCall }

    func connect() {
        guard let url = URL(string: WSClient.serverURL) else { return }
        task?.cancel()
        task = session.webSocketTask(with: url)
        task?.resume()
        receive()
    }

    func ensureConnected() { if !connected { connect() } }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure:
                DispatchQueue.main.async { self.connected = false }
            case .success(let message):
                switch message {
                case .data(let data): self.onAudioReceived?(data)
                case .string(let text): self.handleText(text)
                @unknown default: break
                }
                self.receive()
            }
        }
    }

    private func handleText(_ text: String) {
        guard let d = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let type = obj["type"] as? String else { return }
        DispatchQueue.main.async {
            switch type {
            case "device_status": self.deviceOnline = (obj["device_id"] as? String ?? "", obj["online"] as? Bool ?? false)
            case "device_online": self.deviceOnline = (obj["device_id"] as? String ?? "", true)
            case "device_offline": self.deviceOnline = (obj["device_id"] as? String ?? "", false)
            case "call_connected": self.callState = .inCall
            case "call_ended": self.callState = .ended("已结束")
            case "call_result":
                if (obj["success"] as? Bool ?? true) == false { self.callState = .ended(obj["error"] as? String ?? "呼叫失败") }
            default: break
            }
        }
    }

    private func sendJson(_ dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let s = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(s)) { _ in }
    }

    func checkDeviceStatus(_ id: String) { guard !id.isEmpty else { return }; sendJson(["type": "check_device_status", "device_id": id]) }
    func callDevice(_ id: String) { activeDeviceId = id; callState = .calling; sendJson(["type": "call_request", "device_id": id]) }
    func endCall() { activeDeviceId = nil; callState = .idle; sendJson(["type": "call_end"]) }
    func requestWifiScan(_ id: String) { sendJson(["type": "wifi_scan", "device_id": id]) }
    func sendWifiConfig(_ id: String, _ ssid: String, _ pass: String) { sendJson(["type": "wifi_config", "device_id": id, "ssid": ssid, "password": pass]) }
    func sendVolume(_ id: String, _ v: Int) { sendJson(["type": "set_volume", "device_id": id, "volume": v]) }
    func sendFactoryReset(_ id: String) { sendJson(["type": "factory_reset", "device_id": id]) }
    func sendSwitchNetwork(_ id: String, _ mode: String) { sendJson(["type": "switch_network", "device_id": id, "mode": mode]) }
    func sendPairingGpio(_ id: String, _ level: Int) { sendJson(["type": "pairing_gpio", "device_id": id, "level": level]) }

    func sendAudio(_ data: Data) { task?.send(.data(data)) { _ in } }

    func disconnect() { task?.cancel(with: .goingAway, reason: nil); task = nil; connected = false }
}

extension WSClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        DispatchQueue.main.async { self.connected = true }
        sendJson(["type": "connect_app"])
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in self?.sendJson(["type": "ping"]) }
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        DispatchQueue.main.async { self.connected = false }
        pingTimer?.invalidate()
    }
}
