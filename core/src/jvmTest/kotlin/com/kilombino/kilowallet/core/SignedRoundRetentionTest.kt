package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.kilojoin.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Synthetic, no sockets outside FakeRelay and no actual coins. */
class SignedRoundRetentionTest {
    private fun state(): PoolSession.State {
        val key = Hashes.sha256("signed-retention-fixture".encodeToByteArray())
        val script = Address.scriptPubKey(Ec.publicKey(key), ScriptType.P2WPKH)
        val coin = PoolTx.Coin("11".repeat(32), 0, 60_000, Ec.publicKey(key))
        val pool = PoolSession.newPool(50_000, 2.0, 5, 1)
        return PoolSession.newState(pool.terms, true, pool.secret, listOf(coin), listOf(key),
            listOf("m/test"), listOf(script), Address.scriptPubKey(Ec.publicKey(Hashes.sha256("distinct-change".encodeToByteArray())), ScriptType.P2WPKH),
            creatorSecret = pool.creatorSecret, blindPriv = pool.blindPriv)
    }

    private fun retained(phase: PoolSession.Phase, lookupThrows: Boolean = false, signed: Boolean = true) = runBlocking {
        val s = state()
        s.phase = phase
        s.postedSig = signed
        s.phaseDeadline = 1L
        if (phase == PoolSession.Phase.BROADCAST) s.txid = "22".repeat(32)
        val clocks = AtomicInteger()
        val queries = AtomicInteger()
        val env = object : PoolSession.Env {
            override fun now(): Long { clocks.incrementAndGet(); return NostrEvent.nowSeconds() }
            override fun coinUnspent(coin: PoolTx.Coin) = true
            override fun broadcast(rawHex: String): String = error("No complete transaction in this fixture")
            override fun confirmations(txid: String): Int? {
                queries.incrementAndGet()
                if (lookupThrows) error("synthetic backend unavailable")
                return null
            }
            override fun save(state: PoolSession.State) { PoolSession.State.parse(state.toJson()) }
            override fun event(e: PoolSession.Event) {}
        }
        val session = PoolSession(env, s, PoolSessionTest.FakeRelay())
        try {
            session.start()
            // The actual session's initial ticker runs after five seconds.
            delay(6500)
            assertTrue(clocks.get() > 0, "actual ticker must have run")
            if (phase == PoolSession.Phase.BROADCAST) assertTrue(queries.get() > 0)
            if (signed) {
                assertEquals(phase, s.phase, "elapsed time or unavailable history is not conflict proof")
                assertFalse(session.done, "signed input must remain unresolved")
            } else {
                assertEquals(PoolSession.Phase.ABORTED, s.phase)
                assertTrue(session.done)
            }
        } finally { session.dispose() }
    }

    @Test fun unsignedMemberStillExpires() = retained(PoolSession.Phase.SIGNING, signed = false)
    @Test fun unseenBroadcastDoesNotExpireAfterTwelveHours() = retained(PoolSession.Phase.BROADCAST)
    @Test fun unavailableBackendDoesNotReleaseSignedInput() = retained(PoolSession.Phase.BROADCAST, true)
    @Test fun signedMemberDoesNotExpireAtSignatureDeadline() = retained(PoolSession.Phase.SIGNING)
}
