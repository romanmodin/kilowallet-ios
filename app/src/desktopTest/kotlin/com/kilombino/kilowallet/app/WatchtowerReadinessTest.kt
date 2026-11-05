package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.TransportFactory
import com.kilombino.kilowallet.core.ln.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WatchtowerReadinessTest {
    private class Platform : AppPlatform {
        val db = mutableMapOf<String, String>()
        override val prefs = object : Prefs {
            override fun get(key: String) = db[key]
            override fun put(key: String, value: String?) { if (value == null) db.remove(key) else db[key] = value }
        }
        override val seeds: SeedStore get() = error("No recovery secrets in this test")
        override val transports: TransportFactory get() = error("No network in this test")
        override val unlockName = "test"
        override fun qrImage(text: String): ImageBitmap? = null
        override val canScanQr = false
        override fun scanQr(done: (String?) -> Unit) = done(null)
        override val demoScreen: String? = null
        override val appVersion = "test"
    }
    private class Engine : LndEngine {
        var state = 3
        var stateError = false
        var failTower = false
        var towers = 0
        val methods = mutableListOf<String>()
        override fun start(args: String, cb: LndCallback) = error("No native node")
        override fun stream(method: String, request: ByteArray, cb: LndCallback) = error("No streams")
        override fun call(method: String, request: ByteArray, cb: LndCallback) {
            methods += method
            when (method) {
                "getState" -> if (stateError) cb.onError("starting") else cb.onResponse(Pb().int(1, state.toLong()).build())
                "watchtowerClientAddTower" -> { towers++; if (failTower) cb.onError("tower unavailable") else cb.onResponse(ByteArray(0)) }
                else -> error("Unexpected RPC: $method")
            }
        }
    }
    private fun fixture(block: suspend (LightningModel, Platform, Engine) -> Unit) = runBlocking {
        val old = runCatching { Lnd.engine }.getOrNull()
        val e = Engine(); val p = Platform(); Lnd.engine = e
        try { block(LightningModel(p), p, e) } finally { if (old != null) Lnd.engine = old }
    }
    @Test fun rpcActiveWaitsThenServerActiveRegistersWithoutAnyPeerCall() = fixture { m, p, e ->
        m.addTowerIfServerReady(); assertEquals(0, e.towers); assertNull(p.db["ln.tower_added"])
        e.state = 4; m.addTowerIfServerReady()
        assertEquals(1, e.towers); assertEquals("1", p.db["ln.tower_added"])
        assertFalse(e.methods.contains("connectPeer"))
    }
    @Test fun everyEarlierStateAndUnknownMustWait() = fixture { m, p, e ->
        for (state in listOf(0, 1, 2, 3, 255, 123)) { e.state = state; m.addTowerIfServerReady() }
        assertEquals(0, e.towers); assertTrue(p.db.isEmpty())
    }
    @Test fun unavailableStateDoesNotPretendRegistrationSucceeded() = fixture { m, p, e ->
        e.stateError = true; m.addTowerIfServerReady(); assertEquals(0, e.towers); assertTrue(p.db.isEmpty())
    }
    @Test fun failedTowerRegistrationRetriesWithoutPersistingSuccess() = fixture { m, p, e ->
        e.state = 4; e.failTower = true; m.addTowerIfServerReady()
        assertEquals(1, e.towers); assertTrue(p.db.isEmpty())
        e.failTower = false; m.addTowerIfServerReady()
        assertEquals(2, e.towers); assertEquals("1", p.db["ln.tower_added"])
    }
    @Test fun successfulRegistrationIsIdempotentAcrossModelRecreation() = fixture { m, p, e ->
        e.state = 4; m.addTowerIfServerReady(); m.addTowerIfServerReady()
        LightningModel(p).addTowerIfServerReady(); assertEquals(1, e.towers)
    }
}
