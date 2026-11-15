package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.Signer
import kotlin.test.*

class JoinBlaketCommitmentsTest {
    private class Memory : Prefs {
        val data = mutableMapOf<String, String>()
        var flushes = 0
        var failFlush = false
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun flush() { if (failFlush) error("flush failed"); flushes++ }
    }
    private val words = List(11) { "abandon" } + "about"

    @Test fun accountsStayIsolatedAcrossReopenAndBackupMerge() {
        val p = Memory()
        val x = Signer.accountZpub(words, "")
        val y = Signer.accountZpub(words, "synthetic-second-wallet")
        val a = walletJoinBlaketCommitments(p, x)
        val b = walletJoinBlaketCommitments(p, y)
        a.markUsed("aa".repeat(32))
        assertEquals(1, p.flushes)
        assertFalse(b.isUsed("aa".repeat(32)))
        b.markUsed("bb".repeat(32))
        val backup = a.export()!!
        b.mergeFrom(backup)
        assertEquals(2, b.count())
        assertEquals(1, walletJoinBlaketCommitments(p, x).count())
        assertFalse(a.isUsed("bb".repeat(32)))
        assertEquals(2, walletJoinBlaketCommitments(p, y).count())
        assertNull(p.get("joinblaket.commitments"))
    }

    @Test fun invalidIdentityAndFlushFailureCannotReportSuccess() {
        val p = Memory()
        assertFails { walletJoinBlaketCommitments(p, "invalid") }
        assertTrue(p.data.isEmpty())
        val a = walletJoinBlaketCommitments(p, Signer.accountZpub(words, ""))
        p.failFlush = true
        assertFails { a.markUsed("cc".repeat(32)) }
        assertFails { a.markUsed("cc".repeat(32)) }
        p.failFlush = false
        a.markUsed("cc".repeat(32))
        assertEquals(1, p.flushes)
    }
    @Test fun backupAdapterRejectsMalformedHistoryAndPropagatesFailedDurability() {
        val p = Memory()
        val key = Signer.accountZpub(words, "")
        val parse = kotlinx.serialization.json.Json
        assertFails { mergeJoinBlaketBackup(p, key, parse.parseToJsonElement("[]")) }
        assertFails { mergeJoinBlaketBackup(p, key, parse.parseToJsonElement("{\"used\":[\"bad\"]}")) }
        assertTrue(p.data.isEmpty())
        val valid = parse.parseToJsonElement("{\"used\":[\"" + "aa".repeat(32) + "\"]}")
        p.failFlush = true
        assertFails { mergeJoinBlaketBackup(p, key, valid) }
        assertFails { mergeJoinBlaketBackup(p, key, valid) }
        p.failFlush = false
        mergeJoinBlaketBackup(p, key, valid)
        assertEquals(1, p.flushes)
        assertEquals(1, walletJoinBlaketCommitments(p, key).count())
    }

    @Test fun legacyGlobalHistoryIsConservativelyImportedWithoutDeletingIt() {
        val p = Memory()
        val legacy = "{\"used\":[\"" + "dd".repeat(32) + "\"]}"
        p.data["joinblaket.commitments"] = legacy
        val x = Signer.accountZpub(words, "")
        val y = Signer.accountZpub(words, "synthetic-second-wallet")
        assertTrue(walletJoinBlaketCommitments(p, x).isUsed("dd".repeat(32)))
        assertTrue(walletJoinBlaketCommitments(p, y).isUsed("dd".repeat(32)))
        assertEquals(legacy, p.data["joinblaket.commitments"])
        p.data["joinblaket.commitments"] = "broken"
        assertFails { walletJoinBlaketCommitments(p, x) }
    }

}
