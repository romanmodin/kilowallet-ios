package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.joinblaket.JsonCommitmentStore
import kotlin.test.*

class CommitmentIntegrityTest {
    private val a = "aa".repeat(32)
    private val b = "bb".repeat(32)

    @Test fun corruptHistoryBlocksReadWriteAndBackupWithoutOverwriting() {
        for (bad in listOf("{broken", "{}", "{\"used\":null}", "{\"used\":[12]}", "{\"used\":[\"bad\"]}")) {
            var saved = bad
            val store = JsonCommitmentStore({ saved }, { saved = it })
            assertFails { store.isUsed(a) }
            assertFails { store.markUsed(a) }
            assertFails { store.mergeFrom(JsonCommitmentStore.format(listOf(b))) }
            assertFails { store.export() }
            assertEquals(bad, saved)
        }
    }

    @Test fun invalidBackupIsRejectedAtomicallyAndRestoreOnlyAdds() {
        var saved: String? = null
        val store = JsonCommitmentStore({ saved }, { saved = it })
        store.markUsed(a.uppercase())
        val before = saved
        assertFails { store.mergeFrom("{\"used\":[\"$b\",\"invalid\"]}") }
        assertEquals(before, saved)
        store.mergeFrom(JsonCommitmentStore.format(listOf(b, a)))
        val reopened = JsonCommitmentStore({ saved }, { saved = it })
        assertTrue(reopened.isUsed(a)); assertTrue(reopened.isUsed(b))
        assertEquals(2, reopened.count())
        assertFails { reopened.markUsed("bad") }
        assertEquals(2, reopened.count())
    }

    @Test fun failedPersistencePropagatesToCaller() {
        val store = JsonCommitmentStore({ null }, { error("disk failure") })
        assertFails { store.markUsed(a) }
        assertFalse(store.isUsed(a))
    }
    @Test fun backupRetryMustFlushEvenWhenCacheAlreadyContainsTheUnion() {
        var disk = JsonCommitmentStore.format(listOf(a))
        var cache = disk
        var failFlush = true
        var attempts = 0
        val store = JsonCommitmentStore({ cache }, {
            cache = it
            attempts++
            if (failFlush) error("disk failure")
            disk = cache
        })
        val backup = JsonCommitmentStore.format(listOf(b))
        assertFails { store.mergeFrom(backup) }
        assertFails { store.mergeFrom(backup) }
        assertEquals(2, attempts)
        assertEquals(JsonCommitmentStore.format(listOf(a)), disk)
        failFlush = false
        store.mergeFrom(backup)
        assertEquals(3, attempts)
        val reopened = JsonCommitmentStore({ disk }, { disk = it })
        assertTrue(reopened.isUsed(a))
        assertTrue(reopened.isUsed(b))
        assertEquals(2, reopened.count())
    }
}
