import Foundation

/// IMA-ADPCM 编解码。与心声服务器/硬件端逐字节兼容。
/// 251 字节包：魔数"ADPC" + 版本 + 采样数(480) + 首采样 + 索引 + 保留 + 240字节数据。
final class AdpcmCodec {
    static let packetBytes = 251
    static let pcmFrameBytes = 960
    static let samplesPerFrame = 480
    private static let magic: [UInt8] = [65, 68, 80, 67] // "ADPC"

    private static let indexTable: [Int] = [-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8]
    private static let stepTable: [Int] = [
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,
        107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,
        724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,
        3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,
        15289,16818,18500,20350,22385,24623,27086,29794,32767
    ]

    private var carryIndex = 0
    func resetStreamState() { carryIndex = 0 }

    static func isAdpcmPacket(_ d: [UInt8]) -> Bool {
        d.count >= 8 && Array(d[0..<4]) == magic
    }

    /// 480 个 Int16 (960字节小端) -> 251 字节包
    func encodeFrame(_ pcmLe: [UInt8]) -> [UInt8]? {
        guard pcmLe.count >= AdpcmCodec.pcmFrameBytes else { return nil }
        var samples = [Int16](repeating: 0, count: AdpcmCodec.samplesPerFrame)
        for i in 0..<AdpcmCodec.samplesPerFrame {
            let lo = Int(pcmLe[i*2]); let hi = Int(pcmLe[i*2+1])
            samples[i] = Int16(bitPattern: UInt16(lo | (hi << 8)))
        }
        var out = [UInt8](repeating: 0, count: AdpcmCodec.packetBytes)
        out[0] = 65; out[1] = 68; out[2] = 80; out[3] = 67
        out[4] = 1; out[5] = 0xE0; out[6] = 0x01
        encodeImaBlock(samples, &out, 7)
        return out
    }

    static func decodeToPcm(_ data: [UInt8]) -> [UInt8]? {
        guard isAdpcmPacket(data), data.count >= packetBytes else { return nil }
        let samples = Int(data[5]) | (Int(data[6]) << 8)
        guard samples == samplesPerFrame else { return nil }
        let pcm = decodeImaBlock(data, 7)
        var out = [UInt8](repeating: 0, count: pcmFrameBytes)
        for i in 0..<samplesPerFrame {
            let v = UInt16(bitPattern: pcm[i])
            out[i*2] = UInt8(v & 0xFF); out[i*2+1] = UInt8((v >> 8) & 0xFF)
        }
        return out
    }

    private func encodeImaBlock(_ samples: [Int16], _ out: inout [UInt8], _ offset: Int) {
        let s = Int(samples[0])
        var index = carryIndex
        out[offset] = UInt8(s & 0xFF)
        out[offset+1] = UInt8((s >> 8) & 0xFF)
        out[offset+2] = UInt8(index & 0xFF)
        out[offset+3] = 0
        var step = AdpcmCodec.stepTable[index]
        var bytePos = offset + 4
        var buffer = 0, bufferBits = 0
        var prev = s
        for n in 1..<AdpcmCodec.samplesPerFrame {
            var diff = Int(samples[n]) - prev
            var sign = 0
            if diff < 0 { sign = 8; diff = -diff }
            var code = 0
            var vpdiff = step >> 3
            if diff >= step { code = 4; diff -= step; vpdiff += step }
            let half = step >> 1
            if diff >= half { code |= 2; diff -= half; vpdiff += half }
            let quarter = step >> 2
            if diff >= quarter { code |= 1; vpdiff += quarter }
            prev = AdpcmCodec.clamp16(sign != 0 ? prev - vpdiff : prev + vpdiff)
            index += AdpcmCodec.indexTable[code | sign]
            if index < 0 { index = 0 }; if index > 88 { index = 88 }
            step = AdpcmCodec.stepTable[index]
            buffer |= (code | sign) << bufferBits
            bufferBits += 4
            if bufferBits >= 8 { out[bytePos] = UInt8(buffer & 0xFF); bufferBits -= 8; buffer >>= 8; bytePos += 1 }
        }
        if bufferBits > 0 { out[bytePos] = UInt8(buffer & 0xFF) }
        carryIndex = index
    }

    private static func decodeImaBlock(_ data: [UInt8], _ offset: Int) -> [Int16] {
        var pcm = [Int16](repeating: 0, count: samplesPerFrame)
        var valpred = Int(Int16(bitPattern: UInt16(Int(data[offset]) | (Int(data[offset+1]) << 8))))
        var index = Int(data[offset+2]); if index < 0 { index = 0 }; if index > 88 { index = 88 }
        pcm[0] = Int16(truncatingIfNeeded: valpred)
        var step = stepTable[index]
        var bytePos = offset + 4
        var buffer = 0, bufferBits = 0
        for n in 1..<samplesPerFrame {
            if bufferBits < 4 { buffer |= Int(data[bytePos]) << bufferBits; bufferBits += 8; bytePos += 1 }
            let code = buffer & 0x0F
            buffer >>= 4; bufferBits -= 4
            index += indexTable[code]; if index < 0 { index = 0 }; if index > 88 { index = 88 }
            var vpdiff = step >> 3
            if code & 4 != 0 { vpdiff += step }
            if code & 2 != 0 { vpdiff += step >> 1 }
            if code & 1 != 0 { vpdiff += step >> 2 }
            step = stepTable[index]
            valpred = clamp16(code & 8 != 0 ? valpred - vpdiff : valpred + vpdiff)
            pcm[n] = Int16(truncatingIfNeeded: valpred)
        }
        return pcm
    }

    private static func clamp16(_ v: Int) -> Int { v > 32767 ? 32767 : (v < -32768 ? -32768 : v) }
}
