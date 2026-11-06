package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.ln.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Exercises the real protobuf/RPC boundary without a peer, funds or a network. */
class TowerRegistrationTest {
    private class Engine : LndEngine {
        var state = 4
        var known = false
        var active = true
        var wrongAddress = false
        var rejectAdd = false
        var cancelList = false
        val calls = mutableListOf<String>()
        var request: ByteArray? = null
        override fun start(args: String, cb: LndCallback) = cb.onResponse(null)
        override fun stream(method: String, request: ByteArray, cb: LndCallback) = cb.onError("No stream expected")
        override fun call(method: String, request: ByteArray, cb: LndCallback) {
            calls += method
            when (method) {
                "getState" -> cb.onResponse(Pb().int(1, state).build())
                "watchtowerClientListTowers" -> {
                    if (cancelList) throw CancellationException("cancelled fixture")
                    val reply = if (!known) ByteArray(0) else Pb().msg(1,
                        Pb().bytes(1, Hashes.hexToBytes(Kilombino.TOWER))
                            .string(2, if (wrongAddress) "wrong.onion:9911" else Kilombino.TOWER_HOST)
                            .bool(3, active)).build()
                    cb.onResponse(reply)
                }
                "watchtowerClientAddTower" -> {
                    this.request = request
                    if (rejectAdd) cb.onError("Onion service unavailable")
                    else { known = true; cb.onResponse(ByteArray(0)) }
                }
                else -> cb.onError("Unexpected RPC $method; there are no channel peers")
            }
        }
    }
    private fun withEngine(block: suspend (Engine) -> Unit) = runBlocking {
        val previous = runCatching { Lnd.engine }.getOrNull()
        val engine = Engine(); Lnd.engine = engine
        try { block(engine) } finally { if (previous != null) Lnd.engine = previous }
    }
    @Test fun activeServerRegistersWithoutAnyChannelPeer() = withEngine { e ->
        assertTrue(registerDefaultTower(true))
        assertEquals(listOf("getState", "watchtowerClientListTowers", "watchtowerClientAddTower", "watchtowerClientListTowers"), e.calls)
        val sent = PbMsg.parse(assertNotNull(e.request))
        assertContentEquals(Hashes.hexToBytes(Kilombino.TOWER), sent.bytes(1))
        assertEquals(Kilombino.TOWER_HOST, sent.string(2))
    }
    @Test fun allStartupStatesWaitBeforeTakingTheTowerClientLock() = withEngine { e ->
        for (state in listOf(0, 1, 2, 3, 255, 17)) {
            e.state = state; e.calls.clear()
            assertFalse(registerDefaultTower(true), "state $state")
            assertEquals(listOf("getState"), e.calls)
        }
    }
    @Test fun disabledTorNeverAttemptsTheOnionTower() = withEngine { e ->
        assertFalse(registerDefaultTower(false)); assertTrue(e.calls.isEmpty())
    }
    @Test fun existingActiveDefaultTowerIsVerifiedWithoutAddingAgain() = withEngine { e ->
        e.known = true
        assertTrue(registerDefaultTower(true))
        assertFalse("watchtowerClientAddTower" in e.calls)
    }
    @Test fun failureCanRetryAndMustNotClaimRegistration() = withEngine { e ->
        e.rejectAdd = true
        assertFalse(registerDefaultTower(true)); assertFalse(e.known)
        e.rejectAdd = false
        assertTrue(registerDefaultTower(true))
        assertEquals(2, e.calls.count { it == "watchtowerClientAddTower" })
    }
    @Test fun inactiveTowerDoesNotCountAsRegistrationEvenAfterAddAcknowledgement() = withEngine { e ->
        e.active = false
        assertFalse(registerDefaultTower(true)); assertTrue(e.known)
    }
    @Test fun wrongOnionAddressDoesNotCountAsTheDefaultTower() = withEngine { e ->
        e.known = true; e.wrongAddress = true
        assertFalse(registerDefaultTower(true))
    }
    @Test fun cancellationIsNotTreatedAsSuccessfulRegistration() = withEngine { e ->
        e.cancelList = true
        assertFailsWith<CancellationException> { registerDefaultTower(true) }
        assertFalse(e.known)
    }
}
