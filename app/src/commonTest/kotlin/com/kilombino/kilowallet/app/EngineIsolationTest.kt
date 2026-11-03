package com.kilombino.kilowallet.app

import kotlin.test.*

class EngineIsolationTest {
    @Test fun twoWalletsCannotReadOrEraseEachOthersChannelRestore() {
        val db = mutableMapOf<String, String>()
        val base = object : Prefs {
            override fun get(key: String) = db[key]
            override fun put(key: String, value: String?) { if (value == null) db.remove(key) else db[key] = value }
        }
        val a = EnginePrefs(base, "wallet-" + "a".repeat(64))
        val b = EnginePrefs(base, "wallet-" + "b".repeat(64))
        a.put("ln.restore_scb", "synthetic-channel-backup")
        assertNull(b.get("ln.restore_scb"))
        b.put("ln.restore_scb", "different")
        b.put("ln.restore_scb", null)
        assertEquals("synthetic-channel-backup", a.get("ln.restore_scb"))
        assertFails { EnginePrefs(base, "../different-wallet") }
    }
    @Test fun liveNativeEngineCannotBeReboundByASecondWalletOrBackgroundWorker() {
        val lease = EngineLease()
        assertTrue(lease.claim("first"))
        assertFalse(lease.permits("second"))
        assertFalse(lease.claim("second"))
        assertTrue(lease.claim("first"))
    }
}
