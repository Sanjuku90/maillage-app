package app.maillage

import java.nio.ByteBuffer

/** Pure encoder for Maillage BLE fragments; no Android APIs. */
object BleFramer {
    private const val HEADER = 8
    private const val MAX_FRAME = 520
    private const val MAX_PARTS = 64

    fun fragment(frame: ByteArray, maxWrite: Int, sequence: Int): List<ByteArray> {
        if (frame.size !in 1..MAX_FRAME || maxWrite < 20) return emptyList()
        if (frame.size <= maxWrite) return listOf(frame.copyOf())
        val capacity = maxWrite - HEADER
        val count = (frame.size + capacity - 1) / capacity
        if (count !in 1..MAX_PARTS) return emptyList()
        return (0 until count).map { part ->
            val start = part * capacity
            val length = minOf(capacity, frame.size - start)
            ByteBuffer.allocate(HEADER + length).put(0x4d.toByte()).put(0x46.toByte())
                .putInt(sequence).put(part.toByte()).put(count.toByte())
                .put(frame, start, length).array()
        }
    }
}
