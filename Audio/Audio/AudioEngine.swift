import Foundation
import AVFoundation

/// 采集(麦克风→8kHz Int16→ADPCM) 与 播放(ADPCM→8kHz→喇叭)。
/// 使用 voiceChat 模式，系统自带回声消除(AEC)。
final class AudioEngine {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let encoder = AdpcmCodec()

    private let sampleRate: Double = 8000
    private let frameSamples = 480
    private var captureBuf = [Int16]()
    private var converter: AVAudioConverter?
    private let targetFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 8000, channels: 1, interleaved: true)!
    private let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000, channels: 1, interleaved: false)!

    var onEncoded: (([UInt8]) -> Void)?
    private(set) var muted = false

    func start() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setPreferredSampleRate(sampleRate)
        try? session.setActive(true)

        encoder.resetStreamState()

        // 播放链路
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFormat)

        // 采集链路
        let input = engine.inputNode
        let inFormat = input.inputFormat(forBus: 0)
        converter = AVAudioConverter(from: inFormat, to: targetFormat)
        input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in
            self?.handleCapture(buffer)
        }

        engine.prepare()
        try? engine.start()
        player.play()
    }

    private func handleCapture(_ buffer: AVAudioPCMBuffer) {
        guard let converter else { return }
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let outCap = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 16)
        guard let out = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: outCap) else { return }
        var fed = false
        var err: NSError?
        converter.convert(to: out, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true; status.pointee = .haveData; return buffer
        }
        guard err == nil, let ch = out.int16ChannelData else { return }
        let n = Int(out.frameLength)
        for i in 0..<n { captureBuf.append(ch[0][i]) }
        while captureBuf.count >= frameSamples {
            let frame = Array(captureBuf.prefix(frameSamples))
            captureBuf.removeFirst(frameSamples)
            var bytes = [UInt8](repeating: 0, count: frameSamples * 2)
            for i in 0..<frameSamples {
                let v = UInt16(bitPattern: frame[i])
                bytes[i*2] = UInt8(v & 0xFF); bytes[i*2+1] = UInt8((v >> 8) & 0xFF)
            }
            if let pkt = encoder.encodeFrame(bytes), !muted { onEncoded?(pkt) }
        }
    }

    /// 收到网络二进制帧（可能含多包/粘包），按魔数对齐解码播放
    func play(_ data: Data) {
        guard !muted else { return }
        var bytes = [UInt8](data)
        while bytes.count >= AdpcmCodec.packetBytes {
            let head = Array(bytes[0..<AdpcmCodec.packetBytes])
            if AdpcmCodec.isAdpcmPacket(head) {
                bytes.removeFirst(AdpcmCodec.packetBytes)
                if let pcm = AdpcmCodec.decodeToPcm(head) { schedulePcm(pcm) }
            } else {
                // 找下一个魔数
                if let idx = findMagic(bytes), idx > 0 { bytes.removeFirst(idx) } else { break }
            }
        }
    }

    private func findMagic(_ b: [UInt8]) -> Int? {
        guard b.count > 4 else { return nil }
        for i in 1..<(b.count - 3) where b[i] == 65 && b[i+1] == 68 && b[i+2] == 80 && b[i+3] == 67 { return i }
        return nil
    }

    private func schedulePcm(_ pcm: [UInt8]) {
        let count = pcm.count / 2
        guard let buf = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(count)) else { return }
        buf.frameLength = AVAudioFrameCount(count)
        let ch = buf.floatChannelData![0]
        for i in 0..<count {
            let lo = Int(pcm[i*2]); let hi = Int(pcm[i*2+1])
            let s = Int16(bitPattern: UInt16(lo | (hi << 8)))
            ch[i] = Float(s) / 32768.0
        }
        player.scheduleBuffer(buf, completionHandler: nil)
    }

    func setMuted(_ m: Bool) { muted = m }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        player.stop()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false)
        captureBuf.removeAll()
    }
}
