import Foundation

/// 把 WebSocket 与音频引擎接起来：接通时启动采集/播放。
final class CallController: ObservableObject {
    private let engine = AudioEngine()
    private let ws = WSClient.shared

    init() {
        ws.onAudioReceived = { [weak self] data in self?.engine.play(data) }
        engine.onEncoded = { [weak self] pkt in self?.ws.sendAudio(Data(pkt)) }
    }

    func startAudio() { engine.start() }
    func stopAudio() { engine.stop() }
    func setMuted(_ m: Bool) { engine.setMuted(m) }
}
