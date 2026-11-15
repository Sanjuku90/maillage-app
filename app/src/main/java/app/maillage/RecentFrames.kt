package app.maillage

/** Bounded cache core for already-encrypted group frames. */
internal class RecentFrames(private val maxCount: Int, private val maxAgeMillis: Long) {
    private data class Entry(val createdAt: Long, val frame: ByteArray)
    private val entries = java.util.ArrayDeque<Entry>()

    init { require(maxCount > 0 && maxAgeMillis > 0) }

    fun add(frame: ByteArray, now: Long) {
        expire(now)
        entries.addLast(Entry(now, frame.copyOf()))
        while (entries.size > maxCount) entries.removeFirst()
    }

    fun snapshot(now: Long): List<ByteArray> {
        return snapshotRecords(now).map { it.second }
    }

    fun snapshotRecords(now: Long): List<Pair<Long, ByteArray>> {
        expire(now)
        return entries.map { it.createdAt to it.frame.copyOf() }
    }

    fun clear() = entries.clear()

    private fun expire(now: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now < entry.createdAt || now - entry.createdAt >= maxAgeMillis) iterator.remove()
        }
    }
}
