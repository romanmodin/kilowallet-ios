package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.*
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JoinBlaketModelTest {
    private val words = List(11) { "abandon" } + "about"
    private val to = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    private class Platform : AppPlatform {
        val data = mutableMapOf<String, String>()
        var loads = 0
        var loadedId: String? = null
        var unlock: ((String?, String?) -> Unit)? = null
        var connections = 0
        var flushes = 0
        override val prefs = object : Prefs {
            override fun get(key: String) = data[key]
            override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
            override fun flush() { flushes++ }
        }
        override val seeds = object : SeedStore {
            override fun hasSeed(walletId: String) = true
            override fun load(walletId: String, reason: String, done: (String?, String?) -> Unit) {
                loads++; loadedId = walletId; unlock = done
            }
            override fun save(walletId: String, secret: String, done: (String?) -> Unit) = error("No wallet writes in this test")
            override fun delete(walletId: String): String? = error("No wallet deletion in this test")
        }
        override val transports = TransportFactory { _, _, _, _ -> connections++; error("No network in synthetic tests") }
        override val unlockName = "test"
        override fun qrImage(text: String): ImageBitmap? = null
        override val canScanQr = false
        override fun scanQr(done: (String?) -> Unit) = done(null)
        override val demoScreen: String? = null
        override val appVersion = "test"
    }

    private inner class Fixture(val dispatcher: TestDispatcher) {
        val platform = Platform()
        val key = Signer.accountZpub(words, "")
        var state = UiState(kind = WalletKind.HOT, zpub = key, walletId = WalletCatalog.idFor(key), height = 1000)
        var epoch = 0L
        var frozen = false
        var pending = false
        var rejectAfterReservation = false
        var held = emptySet<String>()
        var gathers = 0
        var verifies = 0
        var signedWrites = 0
        val coin = SpendableUtxo("ab".repeat(32), 0, 100_000, 0, 0, 990)
        val model = JoinBlaketModel(platform, { state },
            gatherCoins = { snapshot -> assertEquals(key, snapshot.zpub); gathers++; listOf(coin) },
            verifyCoins = { snapshot, coins -> assertEquals(key, snapshot.zpub); assertEquals(listOf(coin), coins); verifies++ },
            heldByPools = { held }, poolScripts = { emptySet() }, onSent = {},
            walletEpoch = { epoch },
            checkPolicy = { _, ids -> check(!pending) { "pending" }; check(!frozen || ids.isEmpty()) { "frozen" }; check(!rejectAfterReservation || AddressBook(platform.prefs).next(key, 1) < 2) { "stop before Tor" } },
            persistSigned = { _, _ -> signedWrites++ },
            cryptoDispatcher = dispatcher)
    }

    private fun fixture(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        com.kilombino.kilowallet.app.i18n.I18n.lang.value = com.kilombino.kilowallet.app.i18n.I18n.EN
        val f = Fixture(dispatcher)
        try { block(f) } finally { f.model.close(); advanceUntilIdle(); Dispatchers.resetMain() }
        assertEquals(0, f.platform.connections)
        assertEquals(0, f.signedWrites)
    }

    @Test fun duplicateTapCannotQueueTwoUnlocksAndUsesCapturedSeedIdentity() = fixture { f ->
        var accepted = 0
        f.model.start(to, 10_000, 1.0) { if (it) accepted++ }
        f.model.start(to, 10_000, 1.0) { if (it) accepted++ }
        advanceUntilIdle()
        assertEquals(1, f.gathers)
        assertEquals(1, f.platform.loads)
        assertEquals(f.state.walletId, f.platform.loadedId)
        assertEquals(setOf(f.coin.coinId()), f.model.lockedOutpoints())
        f.platform.unlock!!(null, "cancelled")
        advanceUntilIdle()
        assertFalse(f.model.preparing.value)
        assertTrue(f.model.lockedOutpoints().isEmpty())
        assertEquals(0, accepted)
    }

    @Test fun switchingAwayAndBackInvalidatesLateAuthentication() = fixture { f ->
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        f.epoch += 2
        f.platform.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertEquals(0, f.verifies)
        assertNull(f.model.view.value)
        assertTrue(f.platform.data.isEmpty())
    }

    @Test fun serverChangeDuringUnlockCannotMoveThePayment() = fixture { f ->
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        f.state = f.state.copy(endpoint = NodeEndpoint("offline.invalid", 50002, true))
        f.platform.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertEquals(0, f.verifies)
        assertTrue(f.platform.data.isEmpty())
        assertNull(f.model.view.value)
    }

    @Test fun frozenCoinAfterUnlockStopsBeforeKeyDerivationOrReservation() = fixture { f ->
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        f.frozen = true
        f.platform.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertEquals("frozen", f.model.error.value)
        assertTrue(f.platform.data.isEmpty())
        assertNull(f.model.view.value)
    }

    @Test fun pendingPaymentAndPoolReservationsBlockUnlock() = fixture { f ->
        f.pending = true
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        assertEquals(0, f.platform.loads)
        f.pending = false
        f.held = setOf(f.coin.coinId())
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        assertEquals(0, f.platform.loads)
    }

    @Test fun cancelledPreparationIgnoresLateUnlockAndReleasesOnlyUnsignedLocks() = fixture { f ->
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        val late = f.platform.unlock!!
        f.model.cancel()
        advanceUntilIdle()
        late(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertFalse(f.model.preparing.value)
        assertTrue(f.model.lockedOutpoints().isEmpty())
        assertEquals(0, f.verifies)
        assertTrue(f.platform.data.isEmpty())
    }

    @Test fun failedPreparationDoesNotReuseReservedChangeAfterReopen() = fixture { f ->
        AddressBook(f.platform.prefs).reserve(f.key, 1, 0)
        f.rejectAfterReservation = true
        f.model.start(to, 10_000, 1.0) {}
        advanceUntilIdle()
        f.platform.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertEquals(2, AddressBook(f.platform.prefs).next(f.key, 1))
        assertTrue(f.platform.flushes > 0)
        assertNull(f.model.view.value)
        assertEquals("stop before Tor", f.model.error.value)
        assertTrue(f.model.lockedOutpoints().isEmpty())
    }
}
