package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.chain.CertificateChangedException
import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.chain.ElectrumException
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.joinblaket.ChainLookup
import com.kilombino.kilowallet.core.joinblaket.CommitmentStore
import com.kilombino.kilowallet.core.joinblaket.DirectoryClient
import com.kilombino.kilowallet.core.joinblaket.DirectoryException
import com.kilombino.kilowallet.core.joinblaket.ElectrumChainLookup
import com.kilombino.kilowallet.core.joinblaket.IoauthCheck
import com.kilombino.kilowallet.core.joinblaket.JbNetwork
import com.kilombino.kilowallet.core.joinblaket.JoinBlaketDirectory
import com.kilombino.kilowallet.core.joinblaket.JoinBlaketMarket
import com.kilombino.kilowallet.core.joinblaket.JsonCommitmentStore
import com.kilombino.kilowallet.core.joinblaket.NickIdentity
import com.kilombino.kilowallet.core.joinblaket.TakerCoin
import com.kilombino.kilowallet.core.joinblaket.TakerEnv
import com.kilombino.kilowallet.core.joinblaket.TakerPlan
import com.kilombino.kilowallet.core.joinblaket.TakerRequest
import com.kilombino.kilowallet.core.joinblaket.TakerResult
import com.kilombino.kilowallet.core.joinblaket.TakerSession
import com.kilombino.kilowallet.core.joinblaket.TakerStep
import com.kilombino.kilowallet.core.joinblaket.TakerText
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.tor.Tor
import com.kilombino.kilowallet.core.wallet.Scanner
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * PAY THROUGH JOINBLAKET, the app's side (Kilowallet for Android 1.6.0's `JoinBlaketPay`, without
 * its foreground service): SEND hands over a payment ([start]), the round runs as a JoinMarket
 * taker ([TakerSession]) while the app is open, and [view] tells the JOINBLAKET screen where it is.
 * The user confirms the plan (makers' fees and mining fee) before anything about the coins leaves
 * the phone ([answer]).
 *
 * iOS keeps no app running in the background: leaving the app keeps the round going for the
 * short time iOS allows (a background task), as Kilojoin rounds do; the screen says so.
 *
 * One round at a time. While it runs its coins are kept out of other payments and pools
 * ([lockedOutpoints]) and its change address is held ([heldChangeScripts]); the coins' keys live
 * only in the request and are dropped when the round ends. Used PoDLE commitments are kept in the
 * app's settings (written to disk before each is shown) and travel in the .kab as Android's
 * `joinblaket_commitments`.
 */
class JoinBlaketModel(
    private val platform: AppPlatform,
    private val wallet: () -> UiState,
    /** The wallet's spendable native SegWit coins, read from its server. */
    private val gatherCoins: suspend (UiState) -> List<SpendableUtxo>,
    /** Check coins against the transactions that made them before any key is used for them. */
    private val verifyCoins: suspend (UiState, List<SpendableUtxo>) -> Unit,
    /** Outpoints Kilojoin holds (seated or in a round): never offered to a JoinBlaket round. */
    private val heldByPools: () -> Set<String>,
    /** Scripts Kilojoin counts on: never handed out as a JoinBlaket change address. */
    private val poolScripts: () -> Set<List<Byte>>,
    private val onSent: () -> Unit,
    private val walletEpoch: () -> Long,
    private val checkPolicy: (UiState, List<String>) -> Unit,
    private val persistSigned: (UiState, String) -> Unit,
    private val cryptoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,

) {
    /** The round as the screen shows it. */
    data class View(
        val amount: Long,
        val recipient: String,
        val step: TakerStep? = null,
        /** Set while the plan waits for the user's answer. */
        val plan: TakerPlan? = null,
        val result: TakerResult? = null,
        val deliveryPending: Boolean = false,
    ) {
        val finished: Boolean get() = result != null
        /** Signed and on its way: cancelling no longer stops it. */
        val pastReturn: Boolean get() = step == TakerStep.Broadcasting
    }

    /** The send screen's market: the last one read, or why it could not be. */
    data class MarketView(val market: JoinBlaketMarket.Market? = null, val failed: Boolean = false)

    private class Request(
        val recipient: String, val amount: Long, val feeRate: Double,
        val coins: List<TakerCoin>, val changeAddress: String, val changeScript: ByteArray,
        val snapshot: UiState, val epoch: Long,
    ) {
        var persisted = false
        fun wipe() = coins.forEach { it.privateKey.fill(0) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _view = MutableStateFlow<View?>(null)
    val view: StateFlow<View?> = _view
    private val _market = MutableStateFlow(MarketView())
    val market: StateFlow<MarketView> = _market
    /** Why PAY THROUGH JOINBLAKET did not start (shown under its button), or null. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Unlocking or getting the coins ready: the button waits. */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    private var request: Request? = null
    private var prepareJob: Job? = null
    private var preparingCoins: Set<String> = emptySet()
    private val addresses = AddressBook(platform.prefs)
    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var background: Any? = null

    val running: Boolean get() = _view.value?.finished == false
    private val demo get() = platform.demoScreen != null

    // ------------------------------------------------------------------ the market

    /** Read the market once (the send screen asks every 30 s while it is open). */
    fun refreshMarket() {
        if (demo) return
        platform.httpGet(JoinBlaketMarket.URL_MARKET) { body ->
            val m = body?.let { runCatching { JoinBlaketMarket.parse(it) }.getOrNull() }
            scope.launch {
                _market.update { if (m != null) MarketView(m) else if (it.market == null) MarketView(failed = true) else it }
            }
        }
    }

    fun clearError() { _error.value = null }

    // ------------------------------------------------------------------ starting a round

    private fun current(snapshot: UiState, epoch: Long) {
        check(epoch == walletEpoch() && wallet().walletId == snapshot.walletId &&
            wallet().zpub == snapshot.zpub && wallet().endpoint == snapshot.endpoint &&
            wallet().pinned == snapshot.pinned && wallet().kind == WalletKind.HOT) {
            "The wallet or server changed. Start the payment again."
        }
    }

    /** Reserve the operation before the first suspension or authentication callback. */
    fun start(recipient: String, amount: Long, feeRate: Double, done: (Boolean) -> Unit) {
        val s = wallet()
        val zpub = s.zpub
        if (s.kind != WalletKind.HOT || zpub == null || s.walletId == null) {
            _error.value = I18n.tr(NOT_SUPPORTED); done(false); return
        }
        if (running || _preparing.value) { _error.value = I18n.tr(BUSY); done(false); return }
        val epoch = walletEpoch()
        _error.value = null
        _preparing.value = true
        prepareJob = scope.launch {
            var prepared: Request? = null
            try {
                current(s, epoch)
                checkPolicy(s, emptyList())
                require(amount > 0 && feeRate.isFinite() && feeRate > 0) { "Enter a positive amount and fee rate." }
                val to = JbNetwork.MAINNET.scriptPubKey(recipient.trim())
                require(to.size == 22 && to[0] == 0.toByte()) { NOT_SUPPORTED }
                val all = gatherCoins(s)
                current(s, epoch)
                val held = heldByPools()
                val coins = all.filter { it.height > 0 && (it.type == null || it.type == ScriptType.P2WPKH) && it.coinId() !in held }
                val need = amount / 5
                require(coins.isNotEmpty()) { NO_COINS }
                checkPolicy(s, coins.map { it.coinId() })
                preparingCoins = coins.map { it.coinId() }.toSet()
                // The advisory market height is not trusted for spending. The session reads the chain.
                val tip = s.height
                if (tip > 0 && coins.none { tip - it.height + 1 >= 5 && it.value >= need }) {
                    error(TakerText.fill(TakerText.NO_PODLE_COIN, 5, need))
                }
                val words = unlock(s, I18n.tr(UNLOCK))
                coroutineContext.ensureActive()
                current(s, epoch)
                verifyCoins(s, coins)
                coroutineContext.ensureActive()
                current(s, epoch)
                checkPolicy(s, coins.map { it.coinId() })
                val change = freshChange(s)
                // Assign inside the dispatcher so cancellation on return can still wipe every key.
                withContext(cryptoDispatcher) {
                    prepared = request(words, s, epoch, coins, recipient, amount, feeRate, change)
                }
                coroutineContext.ensureActive()
                current(s, epoch)
                checkPolicy(s, coins.map { it.coinId() })
                val req = requireNotNull(prepared)
                if (begin(req)) { prepared = null; done(true) } else done(false)
            } catch (e: CancellationException) {
                _error.value = I18n.tr(TakerText.CANCELLED)
            } catch (e: Exception) {
                _error.value = I18n.tr(e.message ?: "Could not prepare the payment.")
                done(false)
            } finally {
                prepared?.wipe()
                preparingCoins = emptySet()
                _preparing.value = false
                prepareJob = null
            }
        }
    }

    private suspend fun unlock(s: UiState, reason: String): Pair<List<String>, String> {
        val (secret, err) = suspendCancellableCoroutine<Pair<String?, String?>> { c ->
            platform.seeds.load(requireNotNull(s.walletId), reason) { secret, error ->
                if (c.isActive) c.resume(secret to error)
            }
        }
        if (secret == null) error(err ?: "Not unlocked.")
        return decodeSecret(secret)
    }

    private fun request(words: Pair<List<String>, String>, snapshot: UiState, epoch: Long,
                        coins: List<SpendableUtxo>, recipient: String, amount: Long,
                        feeRate: Double, change: ByteArray): Request {
        val acct = Bip32.parseExtendedPubKey(requireNotNull(snapshot.zpub))
        val seed = Bip39.toSeed(words.first, words.second)
        val master = try { Bip32Priv.fromSeed(seed) } finally { seed.fill(0) }
        val taker = mutableListOf<TakerCoin>()
        try {
            for (u in coins) {
                val node = Bip32Priv.derivePath(master, "m/84'/0'/0'/${u.chainIndex}/${u.index}")
                try {
                    val pub = Bip32.derivePath(acct, u.chainIndex, u.index).pubkey()
                    check(node.publicKey().contentEquals(pub)) { "These recovery words do not belong to this wallet. Nothing was signed." }
                    val coin = TakerCoin(u.txid, u.vout.toLong(), u.value, node.keyBytes(), u.height)
                    taker += coin
                    check(coin.scriptPubKey.contentEquals(Address.scriptPubKey(pub, ScriptType.P2WPKH))) {
                        "The wallet's keys do not match its addresses."
                    }
                } finally { node.wipe() }
            }
            return Request(recipient.trim(), amount, feeRate, taker, JbNetwork.MAINNET.p2wpkhAddress(change), change, snapshot, epoch)
        } catch (e: Throwable) {
            taker.forEach { it.privateKey.fill(0) }
            throw e
        } finally { master.wipe() }
    }

    /** Persist the same address high-water mark used by normal sends and Kilojoin. */
    private fun freshChange(s: UiState): ByteArray {
        val key = requireNotNull(s.zpub)
        addresses.observe(key, s.rows.filter { it.type == null || it.type == ScriptType.P2WPKH })
        val index = addresses.next(key, 1)
        addresses.checkAvailable(key, 1, index)
        val acct = Bip32.parseExtendedPubKey(key)
        val script = Address.scriptPubKey(Bip32.derivePath(acct, 1, index).pubkey(), ScriptType.P2WPKH)
        check(script.toList() !in poolScripts()) { "The change address is reserved by a pool." }
        addresses.reserve(key, 1, index)
        platform.prefs.flush()
        return script
    }

    private fun begin(r: Request): Boolean {
        if (running) return false
        request = r
        _view.value = View(r.amount, r.recipient)
        if (!Tor.available) { finish(TakerResult.Failed(I18n.tr(NO_TOR))); return true }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val chain = AppChain(r.snapshot)
            var result: TakerResult = TakerResult.Cancelled(TakerText.CANCELLED)
            try {
                current(r.snapshot, r.epoch)
                background = platform.beginJoinBlaketWork("JoinBlaket payment")
                result = withContext(ioDispatcher) {
                    TakerSession(Env(chain, r)).run(TakerRequest(r.recipient, r.amount, r.feeRate, r.coins, r.changeAddress))
                }
                if (r.persisted && result !is TakerResult.Sent) result = TakerResult.Failed(TakerText.NOT_BROADCAST)
            } catch (e: CancellationException) {
                result = if (r.persisted) TakerResult.Failed(TakerText.NOT_BROADCAST) else TakerResult.Cancelled(TakerText.CANCELLED)
            } catch (e: Exception) {
                result = TakerResult.Failed(if (r.persisted) TakerText.NOT_BROADCAST else CHAIN, e.message)
            } finally {
                withContext(NonCancellable) {
                    withContext(ioDispatcher) { chain.close() }
                    finish(result)
                }
            }
        }
        return true
    }

    fun answer(yes: Boolean) { answer?.complete(yes) }

    fun cancel() {
        prepareJob?.cancel()
        if (_view.value?.finished == false && request?.persisted != true) {
            answer?.complete(false)
            job?.cancel()
        }
    }

    /** Invalidate authentication and stop work; persisted signed payments remain pending. */
    fun close() { scope.cancel() }

    fun dismiss() {
        if (_view.value?.finished == true) _view.value = null
    }

    fun lockedOutpoints(): Set<String> = preparingCoins +
        if (running) request?.coins?.map { "${it.txid}:${it.vout}" }?.toSet().orEmpty() else emptySet()

    fun heldChangeScripts(): Set<List<Byte>> = setOfNotNull(request?.changeScript?.toList())

    private fun finish(result: TakerResult) {
        val v = _view.value ?: return
        if (v.finished) return
        val deliveryPending = request?.persisted == true
        request?.wipe()
        request = null
        _view.value = v.copy(plan = null, result = result, deliveryPending = deliveryPending)
        answer = null; job = null
        platform.endJoinBlaketWork(background); background = null
        if (result is TakerResult.Sent) onSent()
    }

    // ------------------------------------------------------------------ what the round needs from the app

    private inner class Env(private val appChain: AppChain, private val r: Request) : TakerEnv {
        override suspend fun directory(identity: NickIdentity): DirectoryClient {
            val port = Tor.startAndWait(120_000)
                ?: throw DirectoryException("Tor did not come up: ${Tor.lastLine}")
            val t = platform.directoryTransport(port) ?: throw DirectoryException("No connection through Tor on this platform")
            return DirectoryClient(JoinBlaketDirectory.HOST, JoinBlaketDirectory.PORT, identity, JoinBlaketDirectory.NETWORK.wire,
                JoinBlaketDirectory.ID, requireVerified = true, transport = t, viaTor = true)
        }
        override val chain: ChainLookup = appChain
        override fun broadcast(rawHex: String): String = appChain.broadcast(rawHex)
        override val commitments: CommitmentStore = walletJoinBlaketCommitments(platform.prefs, requireNotNull(r.snapshot.zpub))

        private fun validate() {
            current(r.snapshot, r.epoch)
            checkPolicy(r.snapshot, r.coins.map { "${it.txid}:${it.vout}" })
            check(r.coins.none { "${it.txid}:${it.vout}" in heldByPools() }) { "A payment coin is now reserved by a Kilojoin round. Nothing was signed." }
        }

        override suspend fun confirm(plan: TakerPlan): Boolean = withContext(Dispatchers.Main) {
            validate()
            val d = CompletableDeferred<Boolean>()
            answer = d
            _view.update { it?.copy(plan = plan) }
            try { d.await().also { if (it) validate() } }
            finally { _view.update { it?.copy(plan = null) }; answer = null }
        }

        override suspend fun beforeSign() = withContext(Dispatchers.Main) {
            coroutineContext.ensureActive()
            validate()
        }

        override suspend fun beforeBroadcast(rawHex: String) = withContext(Dispatchers.Main) {
            coroutineContext.ensureActive()
            validate()
            persistSigned(r.snapshot, rawHex)
            r.persisted = true
        }

        override fun progress(step: TakerStep) { _view.update { it?.copy(step = step) } }
    }

    /**
     * Our chain view for the round: the wallet's Electrum server, every coin's block proven by SPV
     * (its merkle branch into a header with real proof of work, as the wallet checks incoming
     * payments). Opened on first use; a call that fails on a dropped connection opens it again once.
     * Blocking, one call at a time (the round makes them one after another).
     */
    private inner class AppChain(private val snapshot: UiState = wallet()) : ChainLookup {
        private var electrum: ElectrumClient? = null
        private var lookup: ChainLookup? = null

        private fun open(): ElectrumClient {
            electrum?.let { return it }
            val c = ElectrumClient(snapshot.endpoint, snapshot.pinned, platform.transports).also { it.connect() }
            electrum = c
            lookup = ElectrumChainLookup(c) { txid, h -> ElectrumChainLookup.spvProof(c, txid, h, Scanner.SPV_FLOOR_BITS) }
            return c
        }

        private fun <T> call(f: (ChainLookup, ElectrumClient) -> T): T {
            val c = open()
            return try { f(lookup!!, c) } catch (e: CertificateChangedException) { throw e } catch (e: ElectrumException) {
                close(); val c2 = open(); f(lookup!!, c2)
            }
        }

        override fun height(): Int = call { l, _ -> l.height() }
        override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? = call { l, _ -> l.utxo(txid, vout, scriptPubKey) }
        override fun blockTime(height: Int): Long = call { l, _ -> l.blockTime(height) }
        fun broadcast(rawHex: String): String = call { _, c -> c.broadcast(rawHex) }

        fun close() {
            runCatching { electrum?.close() }
            electrum = null; lookup = null
        }
    }

    // ------------------------------------------------------------------ screenshots (CI only)

    /** A made-up round for the screenshots: nothing is connected, unlocked or signed. */
    internal fun setDemo(v: View?, market: JoinBlaketMarket.Market? = null) {
        _view.value = v
        if (market != null) _market.value = MarketView(market)
    }

    /**
     * CI screenshot run, real network, no keys: Tor in the app, the JoinBlaket directory by its
     * onion with nick authentication, the orderbook as a watcher asks for it, the bonds valued on
     * the wallet's server with SPV. Returns what to show; prints counts only (never nicks or bonds).
     */
    internal suspend fun liveCheck(): String = withContext(ioDispatcher) {
        val port = Tor.startAndWait(300_000) ?: return@withContext "KW jb-live: Tor did not come up: ${Tor.lastLine}"
        println("KW jb-live: Tor SOCKS port $port")
        val t = platform.directoryTransport(port) ?: return@withContext "KW jb-live: no directory transport"
        "KW jb-live: " + com.kilombino.kilowallet.core.joinblaket.LiveCheck.orderbook(t, { AppChain() }, { (it as AppChain).close() },
            log = { println("KW jb-live: $it") })
    }

    companion object {

        // iOS wording (App Store naming: the round is a JoinBlaket payment, never the protocol's own name).
        const val UNLOCK = "Unlock to let JoinBlaket use your coins"
        const val NO_COINS = "No confirmed bc1q coins to pay from."
        const val BUSY = "Another JoinBlaket payment is going: wait for it to end."
        const val NOT_SUPPORTED = "JoinBlaket needs a spending wallet with native SegWit (bc1q) addresses."
        const val NO_TOR = "Tor can't run on this phone, and the JoinBlaket directory is reached only through Tor."
        const val CHAIN = "Your BTC server could not be reached or its data did not verify. Nothing was sent."
        const val WAIT_CONFIRMATIONS =
            "None of these coins has 5 confirmations yet: makers only accept one that has. The first will in about {0} blocks (~{1} min)."
        const val WAIT_UNCONFIRMED =
            "Your coin is still unconfirmed: it needs {0} confirmations before it can open a JoinBlaket payment (~{1} min)."
        const val NO_BIG_COIN =
            "None of these coins can open this JoinBlaket payment: one needs at least {0} sats (20% of the amount)."

        /**
         * The shared code's reasons (Android's words) as the iOS screens say them: the ones that
         * name the transaction by the protocol's name get their iOS sentence, values kept.
         */
        private val IOS_REASONS: List<Pair<String, String>> by lazy {
            listOf(
                TakerText.COMMITMENT_STORAGE to "The saved JoinBlaket commitment history could not be read or saved. Nothing was signed or sent.",
                TakerText.NO_PODLE_COIN to "None of your coins can open this JoinBlaket payment: one needs at least {0} confirmations and at least {1} sats (20% of the amount).",
                TakerText.NO_FRESH_COMMITMENT to "Your coins that can open a JoinBlaket payment have each been used for three already. Move one to a fresh address of yours and try again.",
                TakerText.NOT_ENOUGH_COINS to "Not enough coins: this payment needs up to {0} sats and your coins add up to {1} sats.",
                TakerText.BAD_TX to "The JoinBlaket transaction did not add up, so it was not signed. Nothing was sent.",
                TakerText.INPUT_SPENT to "A coin of the JoinBlaket transaction was spent meanwhile. Nothing was sent.",
                TakerText.NOT_BROADCAST to "The JoinBlaket transaction was signed but could not be broadcast. It may still go out: wait before spending these coins.",
            )
        }

        /** [reason] (a [TakerText] sentence with its values filled) in the iOS wording, then in the app's language. */
        fun reasonText(reason: String): String {
            for ((android, ios) in IOS_REASONS) {
                val values = TakerText.values(android, reason) ?: continue
                var out = ios
                values.forEachIndexed { i, v -> out = out.replace("{$i}", v) }
                return I18n.tr(out)
            }
            return I18n.tr(reason)
        }
    }
}
