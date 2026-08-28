package com.coco.audio.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IMA-ADPCM 编解码。与心声服务器/硬件端逐字节兼容。
 * 每个数据包 251 字节：4字节魔数"ADPC" + 版本 + 采样数(480) + 首采样值 + 步长索引 + 保留 + 240字节数据。
 */
object AdpcmCodec {
    const val PACKET_BYTES = 251
    const val PCM_FRAME_BYTES = 960
    const val SAMPLES_PER_FRAME = 480
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf(65, 68, 80, 67) // "ADPC"

    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
        449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
        9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    private var carryIndex = 0

    fun resetStreamState() { carryIndex = 0 }

    fun isAdpcmPacket(data: ByteArray): Boolean =
        data.size >= 8 && data.copyOfRange(0, 4).contentEquals(MAGIC)

    fun encodeFrame(pcmLe: ByteArray): ByteArray? {
        if (pcmLe.size < PCM_FRAME_BYTES) return null
        val pcm = ByteBuffer.wrap(pcmLe).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(SAMPLES_PER_FRAME)
        pcm.get(samples, 0, SAMPLES_PER_FRAME)
        val out = ByteArray(PACKET_BYTES)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION
        out[5] = 0xE0.toByte()   // 480 low
        out[6] = 0x01            // 480 high
        encodeImaBlock(samples, out, 7)
        return out
    }

    fun decodeToPcm(data: ByteArray): ByteArray? {
        if (!isAdpcmPacket(data) || data.size < PACKET_BYTES) return null
        val samples = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
        if (samples != SAMPLES_PER_FRAME) return null
        val pcm = decodeImaBlock(data, 7)
        val buf = ByteBuffer.allocate(PCM_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    private fun encodeImaBlock(samples: ShortArray, out: ByteArray, offset: Int) {
        val s = samples[0]
        var index = carryIndex
        out[offset] = (s.toInt() and 0xFF).toByte()
        out[offset + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        out[offset + 2] = index.toByte()
        out[offset + 3] = 0
        var step = STEP_TABLE[index]
        var bytePos = offset + 4
        var buffer = 0
        var bufferBits = 0
        var prev = s.toInt()
        for (n in 1 until SAMPLES_PER_FRAME) {
            var diff = samples[n] - prev
            var sign = 0
            if (diff < 0) { sign = 8; diff = -diff }
            var code = 0
            var vpdiff = step shr 3
            if (diff >= step) { code = 4; diff -= step; vpdiff += step }
            val half = step shr 1
            if (diff >= half) { code = code or 2; diff -= half; vpdiff += half }
            val quarter = step shr 2
            if (diff >= quarter) { code = code or 1; vpdiff += quarter }
            prev = clamp16(if (sign != 0) prev - vpdiff else prev + vpdiff)
            index += INDEX_TABLE[code or sign]
            if (index < 0) index = 0
            if (index > 88) index = 88
            step = STEP_TABLE[index]
            buffer = buffer or ((code or sign) shl bufferBits)
            bufferBits += 4
            if (bufferBits >= 8) {
                out[bytePos] = (buffer and 0xFF).toByte()
                bufferBits -= 8
                buffer = buffer shr 8
                bytePos++
            }
        }
        if (bufferBits > 0) out[bytePos] = (buffer and 0xFF).toByte()
        carryIndex = index
    }

    private fun decodeImaBlock(data: ByteArray, offset: Int): ShortArray {
        val pcm = ShortArray(SAMPLES_PER_FRAME)
        var valpred = ((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
        var index = data[offset + 2].toInt()
        if (index < 0) index = 0
        if (index > 88) index = 88
        pcm[0] = valpred.toShort()
        var step = STEP_TABLE[index]
        var bytePos = offset + 4
        var buffer = 0
        var bufferBits = 0
        for (n in 1 until SAMPLES_PER_FRAME) {
            if (bufferBits < 4) {
                buffer = buffer or ((data[bytePos].toInt() and 0xFF) shl bufferBits)
                bufferBits += 8
                bytePos++
            }
            val code = buffer and 0x0F
            buffer = buffer shr 4
            bufferBits -= 4
            index += INDEX_TABLE[code]
            if (index < 0) index = 0
            if (index > 88) index = 88
            var vpdiff = step shr 3
            if (code and 4 != 0) vpdiff += step
            if (code and 2 != 0) vpdiff += step shr 1
            if (code and 1 != 0) vpdiff += step shr 2
            step = STEP_TABLE[index]
            valpred = clamp16(if (code and 8 != 0) valpred - vpdiff else valpred + vpdiff)
            pcm[n] = valpred.toShort()
        }
        return pcm
    }

    private fun clamp16(v: Int): Int = when {
        v > 32767 -> 32767
        v < -32768 -> -32768
        else -> v
    }
}
