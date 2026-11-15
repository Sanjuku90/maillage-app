package app.maillage

import org.junit.Assert.*
import org.junit.Test

class RecentFramesTest {
    @Test fun expiredItemsAreNotReplayedWhenNoNewMessageHasArrived() {
        val store = RecentFrames(3, 100)
        store.add(byteArrayOf(1), 1000)
        assertEquals(1, store.snapshot(1099).size)
        assertTrue(store.snapshot(1100).isEmpty())
    }
    @Test fun capacityCopyAndClearAreEnforced() {
        val store = RecentFrames(2, 100)
        val first = byteArrayOf(1)
        store.add(first, 0); first[0] = 9
        assertEquals(1.toByte(), store.snapshot(0)[0][0])
        store.add(byteArrayOf(2), 1); store.add(byteArrayOf(3), 2)
        assertEquals(listOf(2, 3), store.snapshot(2).map { it[0].toInt() })
        val copy = store.snapshot(2); copy[0][0] = 9
        assertEquals(2.toByte(), store.snapshot(2)[0][0])
        store.clear(); assertTrue(store.snapshot(2).isEmpty())
    }
    @Test fun backwardClockDoesNotReplayOldItems() {
        val store = RecentFrames(2, 100)
        store.add(byteArrayOf(1), 100)
        assertTrue(store.snapshot(99).isEmpty())
    }
    @Test fun backwardClockRemovesFutureEntriesEvenAfterStillValidEntries() {
        val store = RecentFrames(3, 100)
        store.add(byteArrayOf(1), 10)
        store.add(byteArrayOf(2), 20)
        assertEquals(listOf(1), store.snapshot(15).map { it[0].toInt() })
    }
    @Test fun snapshotRetainsTimestampsWithoutExposingInternalArrays() {
        val store = RecentFrames(3, 100)
        store.add(byteArrayOf(12), 30)
        val saved = store.snapshotRecords(31)
        assertEquals(30L, saved[0].first)
        saved[0].second[0] = 0
        assertEquals(12.toByte(), store.snapshotRecords(31)[0].second[0])
    }
    @Test fun restoredRecordsRemainBoundedAndExpire() {
        val original = RecentFrames(2, 100)
        original.add(byteArrayOf(1), 10)
        original.add(byteArrayOf(2), 20)
        val saved = original.snapshotRecords(30)
        val restored = RecentFrames(2, 100)
        saved.forEach { (time, bytes) -> restored.add(bytes, time) }
        assertEquals(listOf(1, 2), restored.snapshot(30).map { it[0].toInt() })
        assertEquals(listOf(2), restored.snapshot(110).map { it[0].toInt() })
        assertTrue(restored.snapshot(120).isEmpty())
    }
}
