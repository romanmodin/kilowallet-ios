package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.market.Fiat
import com.kilombino.kilowallet.core.market.MarketData
import com.kilombino.kilowallet.core.market.MarketFeed
import com.kilombino.kilowallet.core.wallet.AddressRow
import com.kilombino.kilowallet.core.wallet.ReplacePlanner
import com.kilombino.kilowallet.core.wallet.ScanEvent
import com.kilombino.kilowallet.core.wallet.Scanner
import com.kilombino.kilowallet.core.wallet.SendDraft
import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import com.kilombino.kilowallet.core.wallet.TxConf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.kilombino.kilowallet.core.crypto.TxParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class WalletKind { HOT, WATCH }

/** The main screen's tabs, as on Android: BTC · LIGHTNING · KILOJOIN (Android: COINJOIN) · ARK. */
enum class Tab { BTC, LIGHTNING, POOLS, ARK }

sealed interface Screen {
    data object Welcome : Screen
    data class CreateShow(val words: List<String>) : Screen
    data class CreateConfirm(val words: List<String>, val positions: List<Int>) : Screen
    data object Restore : Screen
    /** Make the words from physical dice (SeedSigner's way): 50 rolls for 12 words, 99 for 24. */
    data object Dice : Screen
    /** A backup file (.kab) picked to restore from: ask its password. */
    data class RestoreFile(val bytes: ByteArray) : Screen
    /** Write a backup file (.kab) of this wallet: a password, then where to save it. */
    data object Backup : Screen
    data object Watch : Screen
    data object Wallets : Screen
    data object Home : Screen
    data object Receive : Screen
    data object Send : Screen
    data object BackupCheck : Screen
    data object Settings : Screen
    /** Speed up ([cancel] false) or cancel an unconfirmed send of this wallet by replacing it (RBF). */
    data class Replace(val txid: String, val cancel: Boolean) : Screen
    /** Speed up an incoming transaction that is stuck by spending our output of it (child pays for parent). */
    data class Cpfp(val txid: String) : Screen
    data class ShowWords(val words: List<String>) : Screen
    /** KILOJOIN: open pools, your rounds, and how it works. */
    data object Pools : Screen
    /** Open a pool of your own (with [Screen.PoolPay]'s payment when it comes from SEND). */
    data object PoolCreate : Screen
    data class PoolJoin(val poolId: String) : Screen
    /** A payment from SEND, to be paid through a new pool: its recipients. */
    data object PoolPay : Screen
    /** The end of Tor's log, to read and copy (Android: SHOW TOR'S LOG). */
    data object TorLog : Screen
}

sealed interface ScanPhase {
    data object Idle : ScanPhase
    data class Scanning(val path: String, val chainIndex: Int, val gap: Int, val gapLimit: Int, val found: Int) : ScanPhase
    data object Done : ScanPhase
    data class Error(val message: String) : ScanPhase
}

sealed interface SendPhase {
    data object Editing : SendPhase
    data object Preparing : SendPhase
    data class Review(val draft: SendDraft, val concern: String?, val changeIndex: Int? = null) : SendPhase
    data object Signing : SendPhase
    data class Pending(val txid: String, val message: String) : SendPhase
    /**
     * Watch-only: the transaction waits for a signer elsewhere (SeedSigner, Sparrow…). [exported] is
     * the PSBT handed out, kept to check that what comes back is the very same transaction.
     */
    class Psbt(val draft: SendDraft, val exported: ByteArray, val coins: List<com.kilombino.kilowallet.core.crypto.Psbt.Coin>, val changeIndex: Int? = null) : SendPhase
    /** [replaced]/[cancelled]: it was a speed-up or a cancellation of an earlier send. */
    data class Sent(val txid: String, val replaced: Boolean = false, val cancelled: Boolean = false) : SendPhase
    data class Failed(val message: String) : SendPhase
}

data class UiState(
    val screen: Screen = Screen.Welcome,
    val wallets: List<WalletInfo> = emptyList(),
    val walletId: String? = null,
    val newWalletName: String = "Wallet 1",
    val settingUp: Boolean = false,
    val hideAmounts: Boolean = false,
    val amountUnit: AmountUnit = AmountUnit.SATS,
    val recipients: List<Recipient> = emptyList(),
    val recipientError: String? = null,
    val backupCheckedAt: Long? = null,
    val checkingBackup: Boolean = false,
    val notes: Map<String, TransactionNote> = emptyMap(),
    val lastSyncedAt: Long? = null,
    val kind: WalletKind? = null,
    val zpub: String? = null,
    val endpoint: NodeEndpoint = NodeEndpoint.DEFAULT,
    val pinned: String? = null,
    val scan: ScanPhase = ScanPhase.Idle,
    val refreshing: Boolean = false,
    val rows: List<AddressRow> = emptyList(),
    val txs: List<TxConf> = emptyList(),
    val height: Int = 0,
    val serverVersion: String? = null,
    /** The server showed a certificate other than the pinned one: its fingerprint, for the user to check. */
    val certChanged: String? = null,
    val send: SendPhase = SendPhase.Editing,
    val suggestedRate: Double? = null,
    val spendable: List<SpendableUtxo>? = null,
    val frozenCoins: Set<String> = emptySet(),
    val selectedCoins: Set<String>? = null,
    val coinPolicyError: String? = null,
    val message: String? = null,
    /** The currency values are shown in ([Fiat.OFF]: none, and the price feed is never asked). */
    val fiat: String = "USD",
    val market: MarketData? = null,
    /** Which tab the main screen shows. */
    val tab: Tab = Tab.BTC,
    /** Simple mode (Android's "simple"): the balance and RECEIVE / SEND, nothing else. */
    val simple: Boolean = false,
    /** Balances and amounts masked (Android's street mode eye). */
    val hidden: Boolean = false,
    /** Street mode: masked every time the app opens. */
    val streetMode: Boolean = false,
    /** Locked with ⏻: nothing is shown until Face ID / Touch ID / the passcode opens it again. */
    val locked: Boolean = false,
    /** Seconds between refreshes while the app is open; 0: by hand only. */
    val refreshSeconds: Int = 300,
    /** When the next refresh is due (epoch ms), for the countdown on the balance. */
    val nextRefreshAt: Long = 0L,
    /** Empty addresses in a row checked before a scan stops. */
    val gapLimit: Int = 20,
    /** The hot wallet's account (its zpub), when there is one (Android: both wallets side by side). */
    val hotZpub: String? = null,
    /** The watch-only key, when there is one, and its type when the key's prefix does not say it (Taproot). */
    val watchKey: String? = null,
    val watchType: ScriptType? = null,
    /** Hot wallet: its Taproot account (BIP-86), derived with the words; scanned and spent once set. */
    val taprootXpub: String? = null,
    /** Hot wallet: new receive and change addresses are Taproot (bc1p) instead of native SegWit. */
    val receiveTaproot: Boolean = false,
) {
    val walletName: String get() = wallets.firstOrNull { it.id == walletId }?.name ?: "Wallet"
    /** One XBT in [fiat], or null (off, or no price yet). */
    val perCoin: Double? get() = if (fiat == Fiat.OFF) null else market?.xbtIn(fiat)

    val confirmed: Long get() = rows.sumOf { it.confirmed }
    val unconfirmed: Long get() = rows.sumOf { it.unconfirmed }
    val total: Long get() = confirmed + unconfirmed
    val scriptType: ScriptType get() = (if (kind == WalletKind.WATCH) watchType else null)
        ?: zpub?.let { runCatching { Bip32.parseExtendedPubKey(it).scriptType }.getOrNull() } ?: ScriptType.P2WPKH
    /** The type a scan reads the active key as (only a Taproot watch-only key needs it said). */
    val keyType: ScriptType? get() = if (kind == WalletKind.WATCH) watchType else null
    /** The Taproot account scanned and spent with the hot wallet's (null for the watch-only one). */
    val activeTaproot: String? get() = if (kind == WalletKind.HOT) taprootXpub else null
    /** Both wallets exist, so the switch moves between them. */
    val hasBoth: Boolean get() = hotZpub != null && watchKey != null
}

/**
 * The app's state and actions — a trimmed port of the Android app's `ui/WalletViewModel.kt` to
 * multiple independent wallets (hot from words, or watch-only from a zpub), the BLAKE2b chain,
 * Electrum only.
 */
class WalletModel(private val platform: AppPlatform, private val cryptoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    networkDispatcher: kotlinx.coroutines.CoroutineDispatcher = com.kilombino.kilowallet.core.platform.ioDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanner = Scanner(platform.transports, dispatcher = networkDispatcher)
    private val prefs = platform.prefs
    private val addresses = AddressBook(prefs)
    private val transactionNotes = TransactionNotes(prefs)
    private val frozenStore = FrozenCoins(prefs)
    private val recipientsStore = Recipients(prefs)
    private var exporting = false
    private var receiveSession: Pair<String, Int>? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    fun close() { cancelAction(); pools.detach(); scope.cancel() }

    private var scanJob: Job? = null
    private var sendJob: Job? = null
    private var actionEpoch = 0L
    private var walletEpoch = 0L
    private var setupBusy = false
    private var catalog: WalletCatalog? = null

    private fun cancelAction() { actionEpoch++; sendJob?.cancel(); sendJob = null }
    private fun isCurrent(epoch: Long, snapshot: UiState) =
        epoch == actionEpoch && state.value.walletId == snapshot.walletId && state.value.zpub == snapshot.zpub && state.value.endpoint == snapshot.endpoint
    private fun pendingKey(zpub: String) = "pending.$zpub"
    private fun pending(zpub: String): SendPhase.Pending? = prefs.get(pendingKey(zpub))?.let { raw ->
        runCatching { SendPhase.Pending(TxParse.txid(TxParse.parse(raw)), "Delivery is unconfirmed. Check or resend this same transaction before making a new payment.") }
            .getOrElse { SendPhase.Pending("unavailable", "The saved pending transaction is unreadable. Do not create another payment until its status is resolved.") }
    }

    /** Clear secret screens and invalidate outstanding unlock callbacks when backgrounded. */
    fun onBackground() {
        cancelAction()
        scanJob?.cancel()
        walletEpoch++
        _state.update { it.copy(screen = if (it.kind == null) Screen.Welcome else Screen.Home,
            send = SendPhase.Editing, checkingBackup = false, selectedCoins = null, spendable = null, scan = ScanPhase.Idle, refreshing = false, message = null) }
    }


    /** Kilojoin: the pools and this wallet's rounds in them. */
    val pools = PoolsModel(platform, { _state.value },
        gatherCoins = {
            val s = _state.value; val zpub = s.zpub ?: error("No wallet.")
            require(pending(zpub) == null) { "Resolve the pending payment before joining a pool." }
            eligibleCoins(scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned), frozenStore.read(zpub), null)
        },
        verifyCoins = { coins ->
            val s = _state.value; val zpub = s.zpub ?: error("No wallet.")
            require(pending(zpub) == null) { "Resolve the pending payment before joining a pool." }
            require(eligibleCoins(coins, frozenStore.read(zpub), null).size == coins.size) { "A selected pool coin is frozen." }
            scanner.verifyCoins(zpub, coins, s.endpoint, s.pinned, taprootXpub = s.activeTaproot)
        },
        onRoundDone = { refresh() },
        checkPolicy = { ids ->
            val zpub = state.value.zpub ?: error("No wallet.")
            require(pending(zpub) == null) { "Resolve the pending payment before using a pool." }
            require(ids.none { it in frozenStore.read(zpub) }) { "A pool input is frozen. Nothing was signed." }
        })

    /** The wallet's mined rewards still locked (shown under the balance), or null before they were looked up. */
    private val _immature = MutableStateFlow<Scanner.Immature?>(null)
    val immature: StateFlow<Scanner.Immature?> = _immature
    private var immatureAsked: String? = null

    /**
     * Looks up the mined rewards that can't be spent yet (what SEND leaves out), again only when the
     * wallet, its balance or the tip changed (Android 1.4.0 `refreshImmature`).
     */
    fun refreshImmature() {
        val s = _state.value
        val rows = s.rows.filter { it.isUsed }
        if (rows.isEmpty() || s.height <= 0) return
        val key = "${s.zpub}|${s.activeTaproot}|${s.height}|${s.total}|${rows.size}"
        if (immatureAsked == key) return
        immatureAsked = key
        if (platform.demoScreen?.substringBefore('@') == "immature") {
            // Screenshot mode: two made-up rewards mined from the long-maturity block, so the note shows.
            val a = SpendableUtxo("c0".repeat(32), 0, 312_500_000, 0, 3, s.height - 1200)
            val b = SpendableUtxo("c1".repeat(32), 0, 312_480_000, 0, 5, s.height - 40)
            _immature.value = Scanner.Immature(s.height, listOf(a, b).map { Scanner.ImmatureCoin(it, Scanner.maturesAt(it.height)) }, 540.0)
            return
        }
        scope.launch {
            runCatching { scanner.immature(rows, s.endpoint, s.pinned) }
                .onSuccess { im -> if (sameWallet(s)) _immature.value = im }
                .onFailure { immatureAsked = null }
        }
    }

    /**
     * The channels a channel.backup holds, read with the node's keys from the recovery words (one
     * unlock): what restoring it will close. A backup of other words is refused ([Scb.WrongSeed]).
     */
    internal fun authenticateWallet(reason: String, done: (Boolean, String?) -> Unit) =
        loadCurrentWords(reason) { secret, err -> done(secret != null, err) }

    private fun loadCurrentWords(reason: String, done: (String?, String?) -> Unit) {
        val s = state.value
        val id = s.walletId
        if (id == null || s.kind != WalletKind.HOT) { done(null, "Select a spending wallet."); return }
        val generation = walletEpoch
        platform.seeds.load(id, reason) { secret, error ->
            if (generation == walletEpoch && state.value.walletId == id) done(secret, error)
        }
    }

    fun readChannelBackup(file: ByteArray, done: (Result<List<com.kilombino.kilowallet.core.ln.Scb.Channel>>) -> Unit) {
        loadCurrentWords(I18n.tr("Read the channel backup")) load@{ secret, error ->
            if (secret == null) { done(Result.failure(IllegalStateException(error ?: "Not unlocked."))); return@load }
            scope.launch {
                done(runCatching {
                    withContext(Dispatchers.Default) {
                        val (words, pass) = decodeSecret(secret)
                        val seed = Bip39.toSeed(words, pass)
                        val root = com.kilombino.kilowallet.core.ln.LnSeed.root(seed)
                        seed.fill(0)
                        try { com.kilombino.kilowallet.core.ln.Scb.decode(file, root) } finally { root.wipe() }
                    }
                })
            }
        }
    }

    /** Lightning: the node on this phone (LND linked in), ported from Android's ln/Lightning.kt. */
    private val offlinePlatform = object : AppPlatform by platform {
        override val lightning: LightningPlatform? = null
        override val ark: ArkPlatform? = null
        override val prefs = object : Prefs {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String?) {}
        }
    }
    private val offlineModels by lazy { LightningModel(offlinePlatform) to ArkModel(offlinePlatform, ::authenticateWallet) }
    private var engineModels: Pair<LightningModel, ArkModel>? = null
    private var modelsId: String? = null
    private fun engineId(): String? = if (platform.demoScreen != null) "demo"
        else state.value.takeIf { it.kind == WalletKind.HOT }?.zpub?.let(WalletCatalog::idFor)
    internal fun engineAccess() = engineId()?.let(nativeEngineLease::permits) == true
    private fun currentEngines(): Pair<LightningModel, ArkModel> {
        val id = engineId() ?: return offlineModels
        if (!nativeEngineLease.claim(id)) return offlineModels
        if (modelsId == id) engineModels?.let { return it }
        val scoped = platform.forEngineWallet(id)
        return (LightningModel(scoped) to ArkModel(scoped, ::authenticateWallet)).also {
            modelsId = id; engineModels = it
        }
    }
    val lightning get() = currentEngines().first
    val ark get() = currentEngines().second


    private companion object {
        const val K_HIDE = "privacy.hideAmounts"
        // Before 1.0 one wallet at a time (kind + key); read once and moved to the keys below.
        const val K_KIND = "wallet.kind"
        const val K_ZPUB = "wallet.zpub"
        const val K_HOT = "wallet.hot.zpub"
        const val K_WATCH = "wallet.watch.key"
        const val K_WATCH_TYPE = "wallet.watch.type"
        const val K_TPUB = "wallet.hot.tpub"
        const val K_RECV_TR = "wallet.hot.taproot"
        const val K_ACTIVE = "wallet.active"
        const val K_HOST = "server.host"
        const val K_PORT = "server.port"
        const val K_FIAT = "fiat"
        const val K_SIMPLE = "ui.simple"
        const val K_STREET = "ui.street"
        const val K_REFRESH = "refresh.seconds"
        const val K_GAP = "scan.gap"
        const val K_TOR_ONLY = "tor.only"
        const val K_TOR_ELECTRUM = "tor.electrum"
        const val K_MARKET = "market.json"
        const val K_MARKET_AT = "market.fetched"
        /** Screenshot mode's price: no network, the same figures every run. */
        const val DEMO_MARKET = """{"xbtUsd":360.0,"xbtEur":321.5,"fiatPerUsd":{"EUR":0.893,"GBP":0.748,"JPY":156.8}}"""
        fun pinKey(e: NodeEndpoint) = "pin.${e.host}:${e.port}"
    }

    init {
        // Tor's two settings (Android: "Only over Tor" on by default, Electrum through Tor off).
        com.kilombino.kilowallet.core.tor.Tor.only = prefs.get(K_TOR_ONLY) != "0"
        com.kilombino.kilowallet.core.tor.Tor.electrum = prefs.get(K_TOR_ELECTRUM) == "1"
        // Back from the background: Tor's port may be gone (iOS suspends the app); it comes back
        // before the node and the pools need it again.
        platform.onForeground { scope.launch { runCatching { com.kilombino.kilowallet.core.tor.Tor.onForeground() } } }
        val host = prefs.get(K_HOST)
        val port = prefs.get(K_PORT)?.toIntOrNull()
        val endpoint = if (host != null && port != null) NodeEndpoint(host, port, true) else NodeEndpoint.DEFAULT
        try {
            val saved = WalletCatalog(prefs)
            saved.value.pending?.let {
                if (platform.seeds.hasSeed(it.id)) saved.finish(select = false) else saved.abort()
            }
            catalog = saved
            val active = saved.value.wallets.firstOrNull { it.id == saved.value.active }
            _state.value = UiState(screen = if (active == null) Screen.Welcome else Screen.Home,
                wallets = saved.value.wallets, walletId = active?.id, kind = active?.kind, zpub = active?.zpub,
                watchType = active?.let { prefs.get(K_WATCH_TYPE + it.id) }?.let(ScriptType::valueOf),
                taprootXpub = active?.let { prefs.get(K_TPUB + it.id) },
                receiveTaproot = active?.let { prefs.get(K_RECV_TR + it.id) } == "1",
                simple = prefs.get(K_SIMPLE) == "1", hidden = prefs.get(K_STREET) == "1", streetMode = prefs.get(K_STREET) == "1",
                refreshSeconds = prefs.get(K_REFRESH)?.toIntOrNull() ?: 300, gapLimit = prefs.get(K_GAP)?.toIntOrNull()?.coerceIn(5,200) ?: 20,
                endpoint = endpoint, pinned = prefs.get(pinKey(endpoint)),
                newWalletName = WalletCatalog.nextName(saved.value.wallets), hideAmounts = prefs.get(K_HIDE) == "true", amountUnit = savedUnit(),
                fiat = prefs.get(K_FIAT) ?: "USD", market = prefs.get(K_MARKET)?.let { j -> runCatching { MarketFeed.parse(j, prefs.get(K_MARKET_AT)?.toLongOrNull() ?: 0L) }.getOrNull() })
            loadNotes()
            if (active != null) scan()
        } catch (e: Exception) {
            _state.value = UiState(endpoint = endpoint, pinned = prefs.get(pinKey(endpoint)),
                message = "Could not open the wallet list. Existing data was not changed. ${e.message ?: ""}")
        }
        refreshPrice()
        if (platform.demoScreen == null) pools.attach()
        if (platform.demoScreen == null && state.value.kind == WalletKind.HOT) lightning.resume()
        if (platform.demoScreen == null && state.value.kind == WalletKind.HOT) ark.resume()
    }

    // ------------------------------------------------------------------ Lightning

    /**
     * Turns Lightning on: the words are unlocked once (Face ID), the node's wallet is made from
     * them and they never reach it otherwise.
     */
    fun enableLightning() {
        val target = lightning
        val started = state.value
        if (!target.available) { say("This build has no Lightning engine."); return }
        loadCurrentWords(I18n.tr("Turn on Lightning")) load@{ secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: Lightning stays off."); return@load }
            scope.launch {
                runCatching {
                    val (words, pass) = decodeSecret(secret)
                    val seed = withContext(Dispatchers.Default) { Bip39.toSeed(words, pass) }
                    if (!sameWallet(started)) { seed.fill(0); return@runCatching }
                    target.chainTip = started.height
                    withContext(Dispatchers.Default) { try { target.enable(seed) } finally { seed.fill(0) } }
                }.onFailure { say(it.message ?: "Lightning did not start.") }
            }
        }
    }

    /**
     * Unlocks the hot wallet's words once (Face ID / passcode) and hands them to [use] with their
     * passphrase, for Ark: its own words are made from them, and restoring compares against them.
     */
    fun withWords(reason: String, use: (List<String>, String) -> Unit) {
        loadCurrentWords(I18n.tr(reason)) load@{ secret, error ->
            if (secret == null) { ark.say("Error: " + (error ?: "not unlocked")); return@load }
            runCatching { decodeSecret(secret) }.onSuccess { (w, p) -> use(w, p) }.onFailure { ark.say("Error: " + (it.message ?: "$it")) }
        }
    }

    // ------------------------------------------------------------------ price (Android 1.2.3: 36 currencies)

    private var priceTriedMs = 0L

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /**
     * The XBT price from mempool.kilombino.com, at most once per the feed's poll interval (5 min),
     * and after a failure not again for 10 minutes. Nothing is asked while prices are off. The
     * request carries nothing about the wallet: it is the same for everyone.
     */
    fun refreshPrice(force: Boolean = false) {
        val s = _state.value
        if (s.fiat == Fiat.OFF) return
        if (platform.demoScreen != null) {
            _state.update { it.copy(market = MarketFeed.parse(DEMO_MARKET, 0L)) }
            return
        }
        val now = nowMs()
        val m = s.market
        if (!force && m != null && now - m.fetchedMs < m.pollMinutes * 60_000L) return
        if (now - priceTriedMs < (if (force) 60_000L else 10 * 60_000L)) return
        priceTriedMs = now
        scope.launch {
            val body = suspendCancellableCoroutine<String?> { c -> platform.httpGet(MarketFeed.API) { c.resume(it) } } ?: return@launch
            val parsed = runCatching { MarketFeed.parse(body, now) }.getOrNull() ?: return@launch
            if (parsed.xbtUsd == null) return@launch
            prefs.put(K_MARKET, body); prefs.put(K_MARKET_AT, now.toString())
            _state.update { it.copy(market = parsed) }
        }
    }

    /** [code]: a [Fiat] currency, or [Fiat.OFF]. One choice for the whole app. */
    fun setFiat(code: String) {
        prefs.put(K_FIAT, code)
        _state.update { it.copy(fiat = code) }
        refreshPrice()
    }

    private fun showCatalog(scanSelected: Boolean = true) {
        val saved = catalog?.value ?: return
        pools.detach()
        cancelAction(); scanJob?.cancel(); walletEpoch++; receiveSession = null
        val active = saved.wallets.firstOrNull { it.id == saved.active }
        val previous = state.value
        _state.value = UiState(screen = if (active == null) Screen.Welcome else Screen.Home,
            wallets = saved.wallets, walletId = active?.id, kind = active?.kind, zpub = active?.zpub,
                watchType = active?.let { prefs.get(K_WATCH_TYPE + it.id) }?.let(ScriptType::valueOf),
                taprootXpub = active?.let { prefs.get(K_TPUB + it.id) },
                receiveTaproot = active?.let { prefs.get(K_RECV_TR + it.id) } == "1",
                simple = prefs.get(K_SIMPLE) == "1", hidden = prefs.get(K_STREET) == "1", streetMode = prefs.get(K_STREET) == "1",
                refreshSeconds = prefs.get(K_REFRESH)?.toIntOrNull() ?: 300, gapLimit = prefs.get(K_GAP)?.toIntOrNull()?.coerceIn(5,200) ?: 20,
            endpoint = previous.endpoint, pinned = prefs.get(pinKey(previous.endpoint)),
            newWalletName = WalletCatalog.nextName(saved.wallets), hideAmounts = prefs.get(K_HIDE) == "true", amountUnit = savedUnit(),
                fiat = prefs.get(K_FIAT) ?: "USD", market = prefs.get(K_MARKET)?.let { j -> runCatching { MarketFeed.parse(j, prefs.get(K_MARKET_AT)?.toLongOrNull() ?: 0L) }.getOrNull() })
        loadNotes()
        if (scanSelected && active != null) scan()
        if (platform.demoScreen == null) pools.attach()
    }

    private fun loadNotes() {
        val zpub = state.value.zpub ?: return
        loadFrozen(zpub)
        runCatching { recipientsStore.read(zpub) }
            .onSuccess { list -> _state.update { it.copy(recipients = list, recipientError = null) } }
            .onFailure { _state.update { it.copy(recipientError = "Saved recipients could not be read. Existing data was preserved.") } }
        _state.update { it.copy(backupCheckedAt = prefs.get(backupKey(zpub))?.toLongOrNull()) }
        runCatching { transactionNotes.read(zpub) }
            .onSuccess { notes -> _state.update { it.copy(notes = notes) } }
            .onFailure { say("Could not read local notes. Existing notes were not changed.") }
    }

    private fun savedUnit() = if (prefs.get("display.unit") == "XBT") AmountUnit.XBT else AmountUnit.SATS
    fun setAmountUnit(unit: AmountUnit) {
        if (state.value.send is SendPhase.Signing) return
        runCatching { prefs.put("display.unit", unit.name) }
            .onSuccess { _state.update { it.copy(amountUnit = unit) } }
            .onFailure { say("Could not save the display unit.") }
    }

    fun saveRecipient(walletId: String, address: String, name: String): Boolean {
        val s = state.value
        if (s.walletId != walletId || s.send is SendPhase.Signing) return false
        val zpub = s.zpub ?: return false
        return runCatching {
            val list = recipientsStore.save(zpub, address, name)
            _state.update { it.copy(recipients = list, recipientError = null) }
            true
        }.getOrElse { say(it.message ?: "Could not save recipient."); false }
    }

    fun removeRecipient(walletId: String, address: String) {
        val s = state.value
        if (s.walletId != walletId || s.send is SendPhase.Signing) return
        val zpub = s.zpub ?: return
        runCatching { recipientsStore.remove(zpub, address) }
            .onSuccess { list -> _state.update { it.copy(recipients = list) } }
            .onFailure { say("Could not remove recipient. Existing data was preserved.") }
    }

    private fun backupKey(zpub: String) = "backup.checked." + WalletCatalog.idFor(zpub)
    fun startBackupCheck() {
        val s = state.value
        if (s.kind != WalletKind.HOT || s.send is SendPhase.Signing) return
        cancelAction()
        val epoch = actionEpoch
        platform.seeds.load(s.walletId ?: WalletCatalog.LEGACY_ID, "Check the written backup for ${s.walletName}") { secret, error ->
            if (!isCurrent(epoch, s)) return@load
            if (secret == null) { say(error ?: "Not unlocked."); return@load }
            go(Screen.BackupCheck)
        }
    }

    fun verifyBackup(words: List<String>, passphrase: String) {
        val s = state.value
        val zpub = s.zpub ?: return
        if (s.screen != Screen.BackupCheck || s.kind != WalletKind.HOT || s.checkingBackup) return
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (!Bip39.isValid(clean)) { say("Check the word count, spelling and order."); return }
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(checkingBackup = true) }
        sendJob = scope.launch {
            try {
                val recovered = withContext(cryptoDispatcher) { Signer.accountZpub(clean, passphrase) }
                if (!isCurrent(epoch, s)) return@launch
                require(WalletCatalog.idFor(recovered) == WalletCatalog.idFor(zpub)) {
                    "These words and passphrase do not match this wallet. Check your written backup."
                }
                val time = platform.nowMillis()
                prefs.put(backupKey(zpub), time.toString())
                _state.update { it.copy(screen = Screen.Settings, checkingBackup = false, backupCheckedAt = time,
                    message = "Backup verified for ${s.walletName}. Keep your written backup secure.") }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (isCurrent(epoch, s)) _state.update { it.copy(checkingBackup = false,
                  message = e.message ?: "Could not verify backup.") } }
        }
    }

    private fun loadFrozen(zpub: String) {
        runCatching { frozenStore.read(zpub) }
            .onSuccess { coins -> _state.update { it.copy(frozenCoins = coins, coinPolicyError = null) } }
            .onFailure { _state.update { it.copy(coinPolicyError = "Frozen coin settings could not be read. Spending is blocked until they can be recovered.") } }
    }

    fun setManualCoins(manual: Boolean) {
        if (state.value.send !is SendPhase.Editing) return
        _state.update { it.copy(selectedCoins = if (manual) emptySet() else null) }
    }

    fun selectCoin(id: String, selected: Boolean) {
        val s = state.value
        if (s.send !is SendPhase.Editing || s.selectedCoins == null || id in s.frozenCoins ||
            s.spendable?.none { it.coinId() == id } != false) return
        _state.update { it.copy(selectedCoins = if (selected) s.selectedCoins + id else s.selectedCoins - id) }
    }

    fun freezeCoin(id: String, freeze: Boolean) {
        val s = state.value
        val zpub = s.zpub ?: return
        if (s.send !is SendPhase.Editing || pending(zpub) != null ||
            (s.spendable?.none { it.coinId() == id } != false && id !in s.frozenCoins)) return
        runCatching { frozenStore.set(zpub, id, freeze) }
            .onSuccess { next -> _state.update { it.copy(frozenCoins = next, selectedCoins = it.selectedCoins?.minus(id), coinPolicyError = null) } }
            .onFailure { loadFrozen(zpub); say("Could not change the frozen coin setting. Existing settings were preserved.") }
    }

    private fun allowedCoins(s: UiState, coins: List<SpendableUtxo>): List<SpendableUtxo> {
        // Read durable policy every time, including the final signing boundary.
        val frozen = frozenStore.read(requireNotNull(s.zpub))
        return eligibleCoins(coins, frozen, s.selectedCoins).filter { it.coinId() !in pools.lockedOutpoints() }
    }

    fun availableCoins(): List<SpendableUtxo> = runCatching {
        allowedCoins(state.value, state.value.spendable ?: emptyList())
    }.getOrDefault(emptyList())

    fun toggleHideAmounts() {
        val hide = !state.value.hideAmounts
        runCatching { prefs.put(K_HIDE, hide.toString()) }
            .onSuccess { _state.update { it.copy(hideAmounts = hide) } }
            .onFailure { say("Could not save the privacy setting.") }
    }

    fun saveTransactionNote(walletId: String, txid: String, label: String, note: String): Boolean {
        val s = state.value
        if (s.walletId != walletId || s.txs.none { it.txid == txid }) return false
        val zpub = s.zpub ?: return false
        return runCatching {
            val notes = transactionNotes.save(zpub, txid, label, note)
            _state.update { it.copy(notes = notes) }
            true
        }.getOrElse { say(it.message ?: "Could not save the note."); false }
    }

    fun exportHistory() {
        val s = state.value
        if (exporting || s.send is SendPhase.Signing) return
        val syncedAt = s.lastSyncedAt ?: run { say("Refresh this wallet successfully before exporting its history."); return }
        val zpub = s.zpub ?: return
        // Read storage again so unreadable notes cannot silently disappear from an export.
        val csv = runCatching { transactionCsv(s.walletName, s.txs, transactionNotes.read(zpub), syncedAt) }
            .getOrElse { say("Could not read local notes for export."); return }
        exporting = true
        try {
            platform.shareCsv("kilowallet-history.csv", csv) { error ->
                exporting = false
                if (error != null && state.value.walletId == s.walletId) say(error)
            }
        } catch (e: Exception) { exporting = false; say("Could not open the share sheet.") }
    }

    private fun poolActionBlocked(): Boolean {
        val active = pools.state.value
        if (active.busy != null || active.rounds.any { it.phase != com.kilombino.kilowallet.core.kilojoin.PoolSession.Phase.ABORTED &&
            it.phase != com.kilombino.kilowallet.core.kilojoin.PoolSession.Phase.CONFIRMED }) {
            say("Finish or leave the active Kilojoin round before changing wallets, servers or making another payment.")
            return true
        }
        return false
    }

    fun selectWallet(id: String) {
        if (poolActionBlocked()) return
        if (setupBusy || state.value.send is SendPhase.Signing) return
        runCatching { requireNotNull(catalog).select(id); showCatalog() }
            .onFailure { say(it.message ?: "Could not select wallet.") }
    }

    fun renameWallet(id: String, name: String) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        runCatching {
            requireNotNull(catalog).rename(id, name)
            _state.update { it.copy(wallets = catalog!!.value.wallets) }
        }.onFailure { say(it.message ?: "Could not rename wallet.") }
    }

    fun addWallet() {
        if (poolActionBlocked()) return
        if (setupBusy || state.value.send is SendPhase.Signing) return
        if (catalog == null) { say("Wallet storage is unavailable. Restart the app before adding a wallet."); return }
        if (state.value.wallets.size >= WalletCatalog.LIMIT) { say("This device supports up to 20 wallets."); return }
        go(Screen.Welcome)
        _state.update { it.copy(newWalletName = WalletCatalog.nextName(it.wallets)) }
    }

    fun setNewWalletName(name: String) {
        if (!setupBusy) _state.update { it.copy(newWalletName = name.take(40)) }
    }

    fun go(screen: Screen) {
        if ((screen in listOf(Screen.Wallets, Screen.Welcome, Screen.Restore, Screen.Watch, Screen.Send) ||
            screen is Screen.Replace || screen is Screen.CreateShow || screen is Screen.CreateConfirm) && poolActionBlocked()) return
        if (setupBusy || _state.value.send is SendPhase.Signing) return
        cancelAction()
        if (screen in listOf(Screen.Wallets, Screen.Welcome, Screen.Restore, Screen.Watch) || screen is Screen.CreateShow || screen is Screen.CreateConfirm) {
            scanJob?.cancel(); walletEpoch++
            _state.update { it.copy(scan = ScanPhase.Idle, refreshing = false, certChanged = null) }
        }
        if (screen != Screen.Receive || state.value.screen != Screen.Receive) receiveSession = null
        _state.update { it.copy(screen = screen, message = null, checkingBackup = false, selectedCoins = if (screen == Screen.Send) it.selectedCoins else null) }
        if (screen == Screen.Send) openSend()
        if (screen == Screen.Pools && platform.demoScreen == null) pools.attach()
        if (screen is Screen.Replace || screen is Screen.Cpfp) {
            _state.update { it.copy(send = SendPhase.Editing) }
            val s = _state.value
            if (platform.demoScreen == null) scope.launch {
                val rate = scanner.suggestedFeeRate(s.endpoint, s.pinned)
                _state.update { it.copy(suggestedRate = rate) }
            }
        }
    }

    fun back() {
        val s = _state.value
        go(when {
            s.kind == null -> Screen.Welcome
            s.screen == Screen.PoolCreate || s.screen is Screen.PoolJoin -> Screen.Pools
            s.screen == Screen.PoolPay -> Screen.Send
            s.screen == Screen.TorLog -> Screen.Settings
            else -> Screen.Home
        })
    }

    // ------------------------------------------------------------------ main screen (Android 1.4.4)

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab, screen = Screen.Home) }
        if (tab == Tab.POOLS && platform.demoScreen == null) pools.attach()
    }

    fun setSimple(on: Boolean) {
        prefs.put(K_SIMPLE, if (on) "1" else null)
        _state.update { it.copy(simple = on, tab = Tab.BTC, screen = Screen.Home) }
    }

    fun toggleHidden() = _state.update { it.copy(hidden = !it.hidden) }

    fun setStreetMode(on: Boolean) {
        prefs.put(K_STREET, if (on) "1" else null)
        _state.update { it.copy(streetMode = on, hidden = on || it.hidden) }
    }

    fun setRefreshSeconds(seconds: Int) {
        prefs.put(K_REFRESH, seconds.toString())
        _state.update { it.copy(refreshSeconds = seconds, nextRefreshAt = if (seconds > 0) nowMs() + seconds * 1000L else 0L) }
    }

    /** The next refresh is due [seconds] from now (the main screen calls it after each one). */
    fun scheduleRefresh() {
        val sec = _state.value.refreshSeconds
        _state.update { it.copy(nextRefreshAt = if (sec > 0) nowMs() + sec * 1000L else 0L) }
    }

    fun setGapLimit(gap: Int) {
        val g = gap.coerceIn(5, 200)
        prefs.put(K_GAP, g.toString())
        _state.update { it.copy(gapLimit = g) }
    }

    /** ⏻: the wallet is locked on screen until it is unlocked again (iOS apps do not quit themselves). */
    fun lock() = _state.update { it.copy(locked = true, hidden = it.streetMode || it.hidden) }

    fun unlock() {
        if (_state.value.kind != WalletKind.HOT || platform.demoScreen != null) { _state.update { it.copy(locked = false) }; return }
        loadCurrentWords(I18n.tr("Unlock Kilowallet")) load@{ secret, error ->
            if (secret == null) { say(error ?: "Not unlocked."); return@load }
            _state.update { it.copy(locked = false) }
        }
    }

    /** Screenshot mode (a throwaway demo wallet, nothing sent anywhere). */
    val isDemo: Boolean get() = platform.demoScreen != null
    val unlockName: String get() = platform.unlockName

    fun dismissMessage() = _state.update { it.copy(message = null) }
    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    // ------------------------------------------------------------------ setting up a wallet

    fun startCreate(words: Int) = go(Screen.CreateShow(Bip39.generate(words)))

    fun wroteDown(words: List<String>) {
        val positions = words.indices.shuffled().take(3).sorted()
        go(Screen.CreateConfirm(words, positions))
    }

    /** The user typed back the words at [positions]: store the seed and open the wallet. */
    fun confirmCreate(words: List<String>, answers: Map<Int, String>): Boolean {
        val confirmation = _state.value.screen as? Screen.CreateConfirm ?: return false
        val ok = words == confirmation.words && answers.keys == confirmation.positions.toSet() &&
            answers.all { (i, w) -> words.getOrNull(i) == w.trim().lowercase() }
        if (!ok) { say("That is not the word written at that position. Check your paper and try again."); return false }
        storeHot(words, "")
        return true
    }

    fun restore(words: List<String>, passphrase: String) {
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (clean.size !in setOf(12, 15, 18, 21, 24)) { say("Enter 12, 15, 18, 21 or 24 words."); return }
        clean.firstOrNull { !Bip39.isWord(it) }?.let { say("\"$it\" is not a recovery word."); return }
        if (!Bip39.isValid(clean)) { say("These words do not form a valid backup (the checksum does not match). Check their order."); return }
        storeHot(clean, passphrase)
    }

    private fun storeHot(words: List<String>, passphrase: String, then: () -> Unit = {}) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        val saved = catalog ?: run { say("Wallet storage is unavailable. Restart the app."); return }
        val name = runCatching { WalletCatalog.cleanName(state.value.newWalletName) }
            .getOrElse { say(it.message ?: "Invalid wallet name."); return }
        setupBusy = true
        _state.update { it.copy(settingUp = true) }
        val epoch = walletEpoch
        scope.launch {
            var seedStored = false
            var staged = false
            try {
                val zpub = withContext(cryptoDispatcher) { Signer.accountZpub(words, passphrase) }
                if (epoch != walletEpoch) return@launch
                val candidate = saved.candidate(name, WalletKind.HOT, zpub)
                // Journal the public identity before saving words. A restart can finish the
                // commit without copying, replacing or revealing any existing seed.
                saved.begin(candidate); staged = true
                val err = suspendCancellableCoroutine { c ->
                    platform.seeds.save(candidate.id, encodeSecret(words, passphrase)) { if (c.isActive) c.resume(it) }
                }
                if (err != null) { saved.abort(); staged = false; say("Could not store the words: $err"); return@launch }
                seedStored = true
                saved.finish(select = epoch == walletEpoch); staged = false
                showCatalog(scanSelected = epoch == walletEpoch)
                if (state.value.walletId == candidate.id) then()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                if (staged && !seedStored) runCatching { saved.abort() }
                say(if (seedStored) "Words were saved safely. Restart the app to finish adding this wallet."
                    else e.message ?: "Could not create the wallet")
            } finally { setupBusy = false; _state.update { it.copy(settingUp = false) } }
        }
    }

    fun watch(xpub: String, type: ScriptType? = null) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        val saved = catalog ?: run { say("Wallet storage is unavailable. Restart the app."); return }
        val raw = xpub.filterNot { it.isWhitespace() }
        val parsed = runCatching { Bip32.parseExtendedPubKey(raw) }.getOrElse { say(it.message ?: "Not a valid key."); return }
        val key = if (parsed.scriptType == ScriptType.P2PKH && type != null && type != ScriptType.P2PKH) Bip32.withScriptType(raw, type) else raw
        runCatching {
            val w = saved.candidate(state.value.newWalletName, WalletKind.WATCH, key)
            prefs.put(K_WATCH_TYPE + w.id, if (type == ScriptType.P2TR) type.name else null)
            saved.addWatch(w)
            showCatalog()
            if (parsed.depth != 3) say("Note: this key is not at account depth (m/purpose'/0'/0').")
        }.onFailure { say(it.message ?: "Could not add watch-only wallet.") }
    }

    /**
     * 🔥 HOT | 👁 WATCH: show the other wallet. Both live side by side (Android 1.4.4): switching
     * changes what is on screen, nothing is deleted.
     */
    fun switchWallet(kind: WalletKind) {
        state.value.wallets.firstOrNull { it.kind == kind }?.let { selectWallet(it.id) }
    }

    /** Remove the wallet on screen (its words too, for the hot one); the other one, if any, stays and is shown. */
    fun deleteWallet() {
        if (poolActionBlocked()) return
        if (setupBusy || state.value.send is SendPhase.Signing) return
        val s = state.value
        val id = s.walletId ?: WalletCatalog.LEGACY_ID
        if (s.zpub?.let { pending(it) } != null) { say("Resolve the pending payment before removing this wallet."); return }
        cancelAction(); scanJob?.cancel(); walletEpoch++
        // Watch-only removal must never touch a hot wallet's Keychain item.
        if (s.kind == WalletKind.HOT) platform.seeds.delete(id)?.let { say("Could not remove the wallet: $it"); return }
        runCatching {
            requireNotNull(catalog).remove(id)
            BalanceWatch.forget(prefs, id)
            val cleared = runCatching { s.zpub?.let { transactionNotes.clear(it); frozenStore.clear(it); recipientsStore.clear(it); prefs.put(backupKey(it), null) } }
            showCatalog()
            if (cleared.isFailure) say("Wallet removed, but some local annotations or settings could not be cleared.")
        }
            .onFailure { say("Could not finish removing the wallet: ${it.message ?: "storage error"}") }
    }

    // ------------------------------------------------------------------ Taproot (Android 1.2.4)

    /**
     * Hot wallet: hand out Taproot (bc1p, BIP-86) or native SegWit (bc1q, BIP-84) addresses for new
     * payments and change. Both accounts are scanned and spent from once Taproot exists, so switching
     * back and forth never hides a coin. A wallet made before 1.0 has no BIP-86 account yet: turning
     * Taproot on then needs the words once.
     */
    fun setReceiveTaproot(on: Boolean) {
        val s = _state.value
        if (s.kind != WalletKind.HOT) return
        fun apply() {
            prefs.put(K_RECV_TR + s.walletId, if (on) "1" else null)
            _state.update { it.copy(receiveTaproot = on) }
            refresh()
        }
        if (!on || s.taprootXpub != null || platform.demoScreen != null) { apply(); return }
        loadCurrentWords(I18n.tr("Turn on Taproot addresses")) load@{ secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: Taproot stays off."); return@load }
            scope.launch {
                val (words, pass) = decodeSecret(secret)
                val tpub = withContext(Dispatchers.Default) { Signer.accountTpub(words, pass) }
                if (!sameWallet(s)) return@launch
                prefs.put(K_TPUB + s.walletId, tpub)
                _state.update { it.copy(taprootXpub = tpub, rows = emptyList()) }
                apply()
                scan()
            }
        }
    }

    /** Show the recovery words after Face ID / Touch ID / passcode. */
    fun revealWords() {
        if (state.value.kind != WalletKind.HOT) return
        cancelAction()
        val epoch = actionEpoch
        val snapshot = state.value
        platform.seeds.load(snapshot.walletId ?: WalletCatalog.LEGACY_ID, "Show recovery words for ${snapshot.walletName}") { secret, error ->
            if (!isCurrent(epoch, snapshot)) return@load
            if (secret == null) { say(error ?: "Not unlocked."); return@load }
            go(Screen.ShowWords(decodeSecret(secret).first))
        }
    }

    // ------------------------------------------------------------------ scanning

    fun scan() {
        val s = _state.value
        val zpub = s.zpub ?: return
        scanJob?.cancel()
        val scanEpoch = walletEpoch
        scanJob = scope.launch {
            _state.update { it.copy(scan = ScanPhase.Scanning("", 0, 0, s.gapLimit, 0), certChanged = null) }
            var found = 0
            val progress = runCatching { addresses.read(zpub) }.getOrElse {
                _state.update { st -> st.copy(scan = ScanPhase.Error(it.message ?: "Invalid address progress"), refreshing = false) }
                return@launch
            }
            scanner.scan(zpub, s.endpoint, s.pinned, gap = s.gapLimit, type = s.keyType, taprootXpub = s.activeTaproot, knownReceiveCount = progress.receive,
                knownChangeCount = progress.change).collect { ev ->
                if (scanEpoch != walletEpoch) return@collect
                if (ev is ScanEvent.Found) {
                    runCatching { addresses.observe(if (ev.row.type == ScriptType.P2TR) s.activeTaproot ?: zpub else zpub, listOf(ev.row)) }.exceptionOrNull()?.let { error ->
                        _state.update { it.copy(scan = ScanPhase.Error(error.message ?: "Could not save address progress"), refreshing = false) }
                        throw CancellationException("Address progress could not be saved")
                    }
                }
                when (ev) {
                    is ScanEvent.Connecting -> Unit
                    is ScanEvent.Connected -> { lightning.chainTip = ev.height; _state.update { it.copy(height = ev.height, serverVersion = ev.server) } }
                    is ScanEvent.Deriving -> _state.update {
                        val p = it.scan as? ScanPhase.Scanning
                        it.copy(scan = ScanPhase.Scanning(ev.path, ev.chainIndex, p?.gap ?: 0, p?.gapLimit ?: s.gapLimit, found))
                    }
                    is ScanEvent.Found -> { found++; _state.update { st -> st.copy(scan = (st.scan as? ScanPhase.Scanning)?.copy(gap = 0, found = found) ?: st.scan) } }
                    is ScanEvent.GapProgress -> _state.update { st ->
                        st.copy(scan = (st.scan as? ScanPhase.Scanning)?.copy(gap = ev.consecutiveEmpty, gapLimit = ev.gapLimit) ?: st.scan)
                    }
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        if (!sameWallet(s)) return@collect
                        _state.update { it.copy(scan = ScanPhase.Done, rows = ev.rows, txs = ev.txs, height = ev.height, lastSyncedAt = platform.nowMillis()) }
                        s.walletId?.let { BalanceWatch.remember(prefs, it, ev.rows) }
                        platform.demoScreen?.substringBefore('@')?.takeIf { it == "home" || it == "simple" || it == "immature" }?.let { platform.markReady(it) }
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(scan = ScanPhase.Error(ev.message)) }
                    is ScanEvent.CertificateChanged -> _state.update { it.copy(scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint) }
                }
            }
            if (_state.value.scan is ScanPhase.Error) platform.demoScreen?.substringBefore('@')?.takeIf { it == "home" || it == "simple" || it == "immature" }?.let { platform.markReady(it) }
        }
    }

    /**
     * Pull-to-refresh and the minute timer: the same gap-limit walk, but quiet (the balance stays on
     * screen) — so a payment to the next unused address is found too, not only to known ones.
     */
    fun refresh() {
        refreshPrice()
        // Preview screens must not start live scans while their synthetic pool state is prepared.
        if (isDemo) return
        val s = _state.value
        if (s.scan is ScanPhase.Scanning || s.refreshing) return
        if (s.rows.isEmpty() && s.scan !is ScanPhase.Done) { scan(); return }
        val zpub = s.zpub ?: return
        val scanEpoch = walletEpoch
        scanJob = scope.launch {
            _state.update { it.copy(refreshing = true) }
            val progress = runCatching { addresses.read(zpub) }.getOrElse {
                _state.update { st -> st.copy(scan = ScanPhase.Error(it.message ?: "Invalid address progress"), refreshing = false) }
                return@launch
            }
            scanner.scan(zpub, s.endpoint, s.pinned, gap = s.gapLimit, type = s.keyType, taprootXpub = s.activeTaproot, knownReceiveCount = progress.receive,
                knownChangeCount = progress.change).collect { ev ->
                if (scanEpoch != walletEpoch) return@collect
                if (ev is ScanEvent.Found) {
                    runCatching { addresses.observe(if (ev.row.type == ScriptType.P2TR) s.activeTaproot ?: zpub else zpub, listOf(ev.row)) }.exceptionOrNull()?.let { error ->
                        _state.update { it.copy(scan = ScanPhase.Error(error.message ?: "Could not save address progress"), refreshing = false) }
                        throw CancellationException("Address progress could not be saved")
                    }
                }
                when (ev) {
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        if (!sameWallet(s)) return@collect
                        _state.update { it.copy(rows = ev.rows, txs = ev.txs, height = ev.height, refreshing = false, scan = ScanPhase.Done, lastSyncedAt = platform.nowMillis()) }
                        s.walletId?.let { BalanceWatch.remember(prefs, it, ev.rows) }
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(refreshing = false, scan = ScanPhase.Error(ev.message), message = ev.message) }
                    is ScanEvent.CertificateChanged -> _state.update {
                        it.copy(refreshing = false, scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint)
                    }
                    else -> Unit
                }
            }
            if (scanEpoch == walletEpoch) _state.update { it.copy(refreshing = false) }
        }
    }

    /** Whether the wallet and server on screen are still those of [started]. */
    private fun sameWallet(started: UiState): Boolean =
        _state.value.zpub == started.zpub && _state.value.endpoint == started.endpoint && _state.value.activeTaproot == started.activeTaproot

    private fun pinIfFirst(endpoint: NodeEndpoint, fp: String?) {
        if (fp == null || prefs.get(pinKey(endpoint)) != null) return
        prefs.put(pinKey(endpoint), fp)
        _state.update { it.copy(pinned = fp) }
    }

    /** Leave the old pin in place (nothing is sent to the server until the user decides). */
    fun dismissCertificate() = _state.update { it.copy(certChanged = null) }

    /** The user checked the new fingerprint and trusts it. */
    fun trustNewCertificate() {
        val s = _state.value
        val fp = s.certChanged ?: return
        prefs.put(pinKey(s.endpoint), fp)
        _state.update { it.copy(pinned = fp, certChanged = null) }
        scan()
    }

    /**
     * "Tell me when the balance changes" with the app closed: the addresses are kept after each
     * scan, and iOS's background refresh checks them (BalanceWatch). Asks for permission to notify.
     */
    fun setWatchNotify(on: Boolean) {
        prefs.put(BalanceWatch.KEY_ON, if (on) "1" else null)
        if (on) _state.value.kind?.let { state.value.walletId?.let { id -> BalanceWatch.remember(prefs, id, _state.value.rows) } }
        else state.value.wallets.forEach { BalanceWatch.forget(prefs, it.id) }
        platform.scheduleBalanceWatch(on)
    }
    fun watchNotify(): Boolean = BalanceWatch.enabled(prefs)

    /** "Only over Tor": what goes over Tor never goes without it (Lightning waits for Tor). */
    fun setTorOnly(on: Boolean) {
        com.kilombino.kilowallet.core.tor.Tor.only = on
        prefs.put(K_TOR_ONLY, if (on) null else "0")
    }

    /** "Connect to the server through Tor": every Electrum server through Tor; scans again that way. */
    fun setElectrumThroughTor(on: Boolean) {
        com.kilombino.kilowallet.core.tor.Tor.electrum = on
        prefs.put(K_TOR_ELECTRUM, if (on) "1" else null)
        if (_state.value.zpub != null) scan()
    }

    fun setServer(host: String, port: Int?) {
        if (poolActionBlocked()) return
        if (setupBusy || state.value.send is SendPhase.Signing) return
        cancelAction(); scanJob?.cancel(); walletEpoch++
        val e = if (host.isBlank() || port == null) NodeEndpoint.DEFAULT
                else NodeEndpoint(host.trim(), port, !(host.trim() == NodeEndpoint.DEFAULT.host && port == NodeEndpoint.DEFAULT.port))
        prefs.put(K_HOST, if (e.isCustom) e.host else null)
        prefs.put(K_PORT, if (e.isCustom) e.port.toString() else null)
        _state.update { it.copy(endpoint = e, pinned = prefs.get(pinKey(e)), rows = emptyList(), txs = emptyList(), lastSyncedAt = null, spendable = null, selectedCoins = null, send = SendPhase.Editing) }
        if (_state.value.zpub != null) scan()
    }

    // ------------------------------------------------------------------ receive

    /** The account new addresses come from: the Taproot one when the hot wallet hands out bc1p. */
    private fun receivingKey(s: UiState): String = requireNotNull(if (receivesTaproot(s)) s.activeTaproot else s.zpub)
    private fun changeScript(s: UiState, index: Int) = SendPlanner.changeScript(receivingKey(s), index, if (receivesTaproot(s)) ScriptType.P2TR else s.keyType)
    private fun receivesTaproot(s: UiState = _state.value) = s.kind == WalletKind.HOT && s.receiveTaproot && s.taprootXpub != null

    /** Rows of the receiving account (the BIP-86 one when Taproot is on, else the wallet's own). */
    private fun receiveRows(s: UiState = _state.value) = s.rows.filter { (it.type == ScriptType.P2TR) == receivesTaproot(s) }

    /** The receiving account's key, read as its type. */
    private fun receiveAccount(s: UiState = _state.value): Bip32.ExtendedPubKey? = if (receivesTaproot(s))
        Bip32.parseExtendedPubKey(s.taprootXpub!!).withType(ScriptType.P2TR)
        else s.zpub?.let { z -> Bip32.parseExtendedPubKey(z).let { p -> s.keyType?.let { p.withType(it) } ?: p } }

    /** One address per Receive visit, stable across recomposition and reserved before display. */
    fun receiveAddress(): Pair<String, String>? {
        val s = _state.value
        val zpub = (if (receivesTaproot(s)) s.taprootXpub else s.zpub) ?: return null
        return runCatching {
            addresses.observe(zpub, receiveRows(s))
            val cached = receiveSession?.takeIf { it.first == zpub }
            val progress = addresses.read(zpub)
            val reuseLatest = cached == null && progress.receive - progress.usedReceive >= AddressBook.GAP
            val index = cached?.second ?: if (reuseLatest) progress.receive - 1 else progress.receive
            val parsed = receiveAccount(s) ?: return null
            val pub = Bip32.derivePath(parsed, 0, index).pubkey()
            val address = Address.encode(pub, parsed.scriptType)
            require(Address.scriptPubKey(pub, parsed.scriptType).toList() !in pools.reservedScripts()) { "Address progress changed. Refresh and try again." }
            if (cached == null) {
                if (reuseLatest) say("Showing your latest unused address. 20 unused addresses are already reserved; refresh after a payment to create another.")
                else addresses.reserve(zpub, 0, index)
                receiveSession = zpub to index
            }
            address to "m/${Scanner.purposeFor(parsed.scriptType)}'/0'/0'/0/$index"
        }.onFailure { say(it.message ?: "Could not reserve a receive address.") }.getOrNull()
    }

    // ------------------------------------------------------------------ send

    private fun openSend() {
        val s = _state.value
        s.zpub?.let { z -> pending(z)?.let { p -> _state.update { it.copy(send = p) }; return } }
        val epoch = actionEpoch
        s.zpub?.let(::loadFrozen)
        _state.update { it.copy(send = SendPhase.Editing, spendable = null, selectedCoins = null) }
        scope.launch {
            val rate = scanner.suggestedFeeRate(s.endpoint, s.pinned)
            if (isCurrent(epoch, s)) _state.update { it.copy(suggestedRate = rate) }
        }
        scope.launch {
            runCatching { scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned) }
                .onSuccess { u -> if (isCurrent(epoch, s)) _state.update { it.copy(spendable = u) } }
                .onFailure { if (isCurrent(epoch, s)) say("Could not load coins: ${it.message}") }
        }
    }

    fun maxSendable(rate: Double): Long = runCatching { SendPlanner.maxSendable(rate, allowedCoins(state.value, state.value.spendable ?: emptyList())) }.getOrDefault(0)

    /** Choose coins and the fee and show the review; nothing secret is touched yet. */
    fun prepareSend(to: String, amountSats: Long, rate: Double, sendAll: Boolean) {
        val s = _state.value
        val zpub = s.zpub ?: return
        if (s.send !is SendPhase.Editing || s.kind == null || (s.kind == WalletKind.WATCH && s.scriptType == ScriptType.P2PKH) || pending(zpub) != null) return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Preparing) }
        sendJob = scope.launch {
            runCatching {
                val utxos = allowedCoins(s, s.spendable ?: scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned)).map { if (s.kind == WalletKind.WATCH) it.copy(type = s.scriptType) else it }
                addresses.observe(receivingKey(s), receiveRows(s))
                val changeIndex = addresses.next(receivingKey(s), 1)
                val draft = withContext(cryptoDispatcher) {
                    SendPlanner.plan(to, amountSats, rate, utxos, changeScript(s, changeIndex), sendAll, useAllInputs = s.selectedCoins != null)
                }
                scanner.verifyCoins(zpub, draft.inputs, s.endpoint, s.pinned, s.keyType, s.activeTaproot)
                if (draft.change > 0) addresses.checkAvailable(receivingKey(s), 1, changeIndex)
                draft to changeIndex.takeIf { draft.change > 0 }
            }.onSuccess { (d, index) -> if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d), index)) } }
             .onFailure { e -> if (e is CancellationException) throw e; if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the send.")) } }
        }
    }

    // ------------------------------------------------------------------ watch-only: sign elsewhere (PSBT)

    /** The account key's child number (bytes 9–12 of the serialised key), for the origin a signer looks for. */
    private fun childNumber(key: String): Long {
        val raw = com.kilombino.kilowallet.core.crypto.Base58.decodeChecked(key.trim())
        return (9..12).fold(0L) { a, i -> (a shl 8) or (raw[i].toLong() and 0xff) }
    }

    /**
     * Hand the reviewed send to a signer as a PSBT (Android 1.1, watch-only): every input asks for the
     * unified sighash 0x21; the change output is marked as ours so the signer can tell it apart.
     */
    fun exportPsbt() {
        val s = _state.value
        val review = s.send as? SendPhase.Review ?: return
        val draft = review.draft
        val key = s.zpub ?: return
        if (s.kind != WalletKind.WATCH || pending(key) != null) return
        runCatching {
            val acct = Bip32.parseExtendedPubKey(key).let { p -> s.keyType?.let { p.withType(it) } ?: p }
            val type = acct.scriptType
            require(type != ScriptType.P2PKH) { "Signing elsewhere works for bc1q, nested SegWit (3…) and Taproot (bc1p) wallets." }
            val origin = com.kilombino.kilowallet.core.crypto.Psbt.Origin.guess(type, acct.depth, childNumber(key))
            val coins = draft.inputs.map { u ->
                com.kilombino.kilowallet.core.crypto.Psbt.Coin(u.txid, u.vout, u.value, type,
                    Bip32.derivePath(acct, u.chainIndex, u.index).pubkey(), origin?.child(u.chainIndex, u.index))
            }
            // Our change: the output paying one of this wallet's change addresses, found by deriving them.
            val firstChange = s.rows.filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1) ?: 0
            val change = draft.outputs.withIndex().mapNotNull { (n, o) ->
                (maxOf(0, firstChange - 5) until firstChange + 25).firstOrNull { i ->
                    Address.scriptPubKey(Bip32.derivePath(acct, 1, i).pubkey(), type).contentEquals(o.scriptPubKey)
                }?.let { i -> n to com.kilombino.kilowallet.core.crypto.Psbt.Change(Bip32.derivePath(acct, 1, i).pubkey(), type, origin?.child(1, i)) }
            }.toMap()
            SendPhase.Psbt(draft, com.kilombino.kilowallet.core.crypto.Psbt.create(coins, draft.outputs, change), coins, review.changeIndex)
        }.onSuccess { p -> _state.update { it.copy(send = p) } }
         .onFailure { e -> _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not make the PSBT.")) } }
    }

    /**
     * What the signer returned (a PSBT as base64, hex or bytes): checked to be the very transaction
     * handed out, every signature verified against our own copy of the coins and required to be a
     * unified (0x21) one, then broadcast.
     */
    fun finishPsbt(returned: ByteArray) {
        val s = _state.value
        val p = s.send as? SendPhase.Psbt ?: return
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.WATCH || pending(zpub) != null) return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Signing) }
        sendJob = scope.launch {
            try {
                val tx = withContext(cryptoDispatcher) { com.kilombino.kilowallet.core.crypto.Psbt.finish(p.exported, returned, p.coins, p.draft.outputs) }
                if (!isCurrent(epoch, s)) return@launch
                require(allowedCoins(s, p.draft.inputs).size == p.draft.inputs.size) { "A reviewed coin is frozen. Edit the payment." }
                p.changeIndex?.let { addresses.reserve(receivingKey(s), 1, it) }
                prefs.put(pendingKey(zpub), tx.rawHex)
                val id = scanner.broadcast(tx.rawHex, s.endpoint, s.pinned)
                prefs.put(pendingKey(zpub), null)
                if (isCurrent(epoch, s)) { _state.update { it.copy(send = SendPhase.Sent(id)) }; refresh() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (isCurrent(epoch, s)) _state.update { it.copy(send = pending(zpub) ?: SendPhase.Failed(e.message ?: "The signed PSBT could not be used.")) }
            }
        }
    }

    /** [finishPsbt] from text: base64, hex, or a single-part ur:crypto-psbt. */
    fun finishPsbtText(text: String) {
        runCatching {
            val t = text.trim()
            if (t.lowercase().startsWith("ur:")) com.kilombino.kilowallet.core.crypto.Ur.Decoder().also { d ->
                require(d.receive(t)) { "That is one part of an animated QR: scan it with the camera." }
            }.psbt() else com.kilombino.kilowallet.core.crypto.Psbt.decodeText(t)
        }.onSuccess { finishPsbt(it) }.onFailure { say(it.message ?: "That is not a PSBT.") }
    }

    /** Read the signer's animated QR with the camera, part by part, then [finishPsbt]. */
    fun scanSignedPsbt() {
        val decoder = com.kilombino.kilowallet.core.crypto.Ur.Decoder()
        var single: String? = null
        platform.scanQrParts(onPart = { text ->
            if (!text.lowercase().startsWith("ur:")) { single = text; true }
            else runCatching { decoder.receive(text) }.getOrDefault(false)
        }, onEnd = {
            val one = single
            when {
                one != null -> finishPsbtText(one)
                runCatching { decoder.psbt() }.isSuccess -> finishPsbt(decoder.psbt())
            }
        })
    }

    /** Unlock the words, sign with the unified sighash (0x21) and broadcast. */
    fun confirmSend() {
        val s = _state.value
        val review = s.send as? SendPhase.Review ?: return
        val draft = review.draft
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT || pending(zpub) != null) return
        try {
            require(allowedCoins(s, draft.inputs).size == draft.inputs.size) { "A reviewed coin is frozen. Edit the payment." }
        } catch (e: Exception) {
            _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Coin policy could not be checked.")) }
            return
        }
        cancelAction()
        val epoch = actionEpoch
        // Set busy BEFORE authentication: rapid taps cannot queue multiple unlocks or broadcasts.
        _state.update { it.copy(send = SendPhase.Signing) }
        platform.seeds.load(s.walletId ?: WalletCatalog.LEGACY_ID, "Sign and send ${com.kilombino.kilowallet.core.wallet.formatSats(draft.amount)} sats from ${s.walletName}") { secret, error ->
            if (!isCurrent(epoch, s)) return@load
            if (secret == null) {
                _state.update { it.copy(send = review) }
                say(error ?: "Not unlocked: nothing was sent.")
                return@load
            }
            sendJob = scope.launch {
                try {
                    // A storage change or corrupt policy must block signing, not unlock frozen coins.
                    val approved = allowedCoins(s, draft.inputs)
                    require(approved.size == draft.inputs.size) { "A reviewed coin is now frozen. Edit the payment." }
                    coroutineContext.ensureActive()
                    if (!isCurrent(epoch, s)) return@launch
                    val (words, pass) = decodeSecret(secret)
                    val signed = withContext(cryptoDispatcher) { Signer.sign(draft, words, pass, zpub, s.activeTaproot) }
                    coroutineContext.ensureActive()
                    if (!isCurrent(epoch, s)) return@launch
                    // Keep the exact signed transaction before the first send. A lost receipt
                    // must not invite a second, different payment.
                    review.changeIndex?.let { addresses.reserve(receivingKey(s), 1, it) }
                    prefs.put(pendingKey(zpub), signed.rawHex)
                    val txid = scanner.broadcast(signed.rawHex, s.endpoint, s.pinned)
                    prefs.put(pendingKey(zpub), null)
                    if (isCurrent(epoch, s)) {
                        pools.leaveFor(draft.inputs.map { it.coinId() })
                        _state.update { it.copy(send = SendPhase.Sent(txid, replaced = draft.replaces != null, cancelled = draft.cancels)) }
                    }
                    refresh()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    if (isCurrent(epoch, s)) _state.update { it.copy(send = pending(zpub) ?: SendPhase.Failed(e.message ?: "Could not sign the transaction.")) }
                }
            }
        }
    }

    /** Retry only the already signed transaction, preserving its transaction ID and outputs. */
    fun retryPendingSend() {
        val s = state.value
        if (s.send !is SendPhase.Pending) return
        val zpub = s.zpub ?: return
        val raw = prefs.get(pendingKey(zpub)) ?: return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Signing) }
        sendJob = scope.launch {
            try {
                val id = scanner.resolveBroadcast(raw, s.endpoint, s.pinned)
                prefs.put(pendingKey(zpub), null)
                if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Sent(id)) }
                refresh()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (isCurrent(epoch, s)) _state.update { it.copy(send = pending(zpub)!!) } }
        }
    }

    fun editSend() {
        if (state.value.send is SendPhase.Signing) return
        cancelAction()
        _state.update { it.copy(send = SendPhase.Editing) }
    }

    // ------------------------------------------------------------------ speed up / cancel (RBF)

    /** The next unused change address's script (one past the highest change address seen, none a pool's seat holds). */
    /** Open a pool for the payment typed in SEND (1 to 5 recipients). */
    fun payThroughPool(recipients: List<Pair<String, Long>>, rate: Double) {
        pools.startPayment(PoolPayRequest(recipients, rate))
        go(Screen.PoolPay)
    }

    /**
     * Draft a replacement of unconfirmed send [txid] (Android: `prepareBump` / `prepareCancel`):
     * the same coins, at [rate] or the least the network takes for a replacement; a speed-up keeps
     * the payment and takes the extra fee from the change, a cancellation sends everything back
     * to a fresh address of this wallet. The review and the signing are a normal send's.
     */
    fun prepareReplace(txid: String, cancel: Boolean, rate: Double) {
        val s = state.value
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT || s.send !is SendPhase.Editing || pending(zpub) != null || poolActionBlocked()) return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Preparing) }
        sendJob = scope.launch {
            try {
                val (tx, coins) = scanner.replacementCoins(txid, s.rows, s.endpoint, s.pinned)
                require(allowedCoins(s, coins).size == coins.size) { "A replacement input is frozen or reserved." }
                addresses.observe(receivingKey(s), receiveRows(s))
                val index = addresses.next(receivingKey(s), 1)
                val d = withContext(cryptoDispatcher) {
                    if (cancel) ReplacePlanner.cancel(txid, tx, coins, changeScript(s, index), rate)
                    else ReplacePlanner.bump(txid, tx, coins, { spk ->
                        Address.electrumScriptHash(spk) in s.rows.filter { it.chainIndex == 1 }.map { it.scriptHash }.toSet()
                    }, rate)
                }
                scanner.verifyCoins(zpub, d.inputs, s.endpoint, s.pinned, s.keyType, s.activeTaproot)
                if (cancel) addresses.checkAvailable(receivingKey(s), 1, index)
                if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d), index.takeIf { cancel })) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the replacement.")) } }
        }
    }

    // ------------------------------------------------------------------ speed up an incoming payment (CPFP)

    /**
     * Pull a stuck unconfirmed transaction that paid this wallet along (child pays for parent,
     * Android `prepareCpfp`): our outputs of it go back to a fresh address of ours with a fee big
     * enough that parent and child together pay [rate]. The review and the signing are a send's.
     */
    fun prepareCpfp(txid: String, rate: Double) {
        val s = _state.value
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT || s.send !is SendPhase.Editing || pending(zpub) != null || poolActionBlocked()) return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Preparing) }
        sendJob = scope.launch {
            try {
                val (fee, vsize, ours) = scanner.cpfpParent(txid, s.rows, s.endpoint, s.pinned)
                if (!isCurrent(epoch, s)) return@launch
                require(allowedCoins(s, ours).size == ours.size) { "A reviewed coin is frozen. Edit the payment." }
                val key = receivingKey(s)
                addresses.observe(key, receiveRows(s))
                val index = addresses.next(key, 1)
                addresses.checkAvailable(key, 1, index)
                val spk = changeScript(s, index)
                require(spk.toList() !in pools.reservedScripts()) { "Address progress changed. Refresh and try again." }
                val d = withContext(cryptoDispatcher) { com.kilombino.kilowallet.core.wallet.CpfpPlanner.plan(txid, fee, vsize, ours, spk, rate) }
                scanner.verifyCoins(zpub, d.inputs, s.endpoint, s.pinned, s.keyType, s.activeTaproot)
                if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d), index)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the speed-up.")) } }
        }
    }

    // ------------------------------------------------------------------ dice (Android 1.2: SeedSigner's way)

    fun startDice() = go(Screen.Dice)

    /** The words for [rolls] (50 → 12 words, 99 → 24), shown to be written down like any new wallet's. */
    fun diceWords(rolls: String) {
        runCatching {
            val bits = if (rolls.length == 50) 128 else 256
            Bip39.fromEntropy(Bip39.entropyFromDiceRolls(rolls, bits))
        }.onSuccess { go(Screen.CreateShow(it)) }.onFailure { say(it.message ?: "Those rolls can't make a seed.") }
    }

    // ------------------------------------------------------------------ backup file (.kab, Android 0.20)

    /**
     * The settings that travel in the backup file, in the Android app's typed format and under its
     * key names, so a file restores the same way on either phone. Never a server: a file one did not
     * make must not point the wallet at someone else's (Android `AppBackup.isServerKey`).
     */
    private fun settingsJson(): kotlinx.serialization.json.JsonObject {
        val s = _state.value
        fun typed(t: String, v: kotlinx.serialization.json.JsonPrimitive) = kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive(t)); put("v", v) }
        return kotlinx.serialization.json.buildJsonObject {
            put("store", kotlinx.serialization.json.buildJsonObject {
                put("fiat", typed("s", kotlinx.serialization.json.JsonPrimitive(s.fiat)))
                put("refresh_open_s", typed("i", kotlinx.serialization.json.JsonPrimitive(s.refreshSeconds)))
                put("coinjoin_enabled", typed("b", kotlinx.serialization.json.JsonPrimitive(pools.state.value.enabled)))
                put("ios_gap_limit", typed("i", kotlinx.serialization.json.JsonPrimitive(s.gapLimit)))
                put("ios_street_mode", typed("b", kotlinx.serialization.json.JsonPrimitive(s.streetMode)))
            })
            // Lightning's birthday and channel backup, under Android's key (AppBackup "lightning").
            lightning.exportBackup()?.let { put("lightning", it) }
        }
    }

    private fun applySettings(app: kotlinx.serialization.json.JsonObject?) {
        val store = app?.get("store") as? kotlinx.serialization.json.JsonObject ?: return
        fun v(k: String) = ((store[k] as? kotlinx.serialization.json.JsonObject)?.get("v") as? kotlinx.serialization.json.JsonPrimitive)
        v("fiat")?.content?.let { if (it == Fiat.OFF || Fiat.ALL.any { f -> f.code == it }) setFiat(it) }
        v("refresh_open_s")?.content?.toIntOrNull()?.let { if (it in listOf(0, 30, 60, 300)) setRefreshSeconds(it) }
        v("ios_gap_limit")?.content?.toIntOrNull()?.let { setGapLimit(it) }
        v("ios_street_mode")?.content?.toBooleanStrictOrNull()?.let { setStreetMode(it) }
        v("coinjoin_enabled")?.content?.toBooleanStrictOrNull()?.let { pools.setEnabled(it) }
        (app["lightning"] as? kotlinx.serialization.json.JsonObject)?.let { runCatching { lightning.stageRestore(it) } }
    }

    /** Make the backup file (words, passphrase and settings, sealed with [password]) and ask where to save it. */
    fun exportBackup(password: String) {
        if (!engineAccess()) { say("Reopen Kilowallet with this wallet selected before backing it up."); return }
        val backupState = state.value
        val epoch = walletEpoch
        if (password.length < com.kilombino.kilowallet.core.backup.Kab.MIN_PASSWORD) { say("Use a password of at least ${com.kilombino.kilowallet.core.backup.Kab.MIN_PASSWORD} characters."); return }
        loadCurrentWords(I18n.tr("Unlock to write the backup file")) load@{ secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: no file was written."); return@load }
            scope.launch {
                val (words, pass) = decodeSecret(secret)
                // Ark's wallet goes in when it runs on the wallet's words or on words derived from them
                // (Android's fullSnapshot); an Ark of other words keeps its own backup.
                val arkFiles = ark.backupFiles(words, pass)
                if (epoch != walletEpoch || !sameWallet(backupState)) return@launch
                val settings = settingsJson()
                val file = withContext(Dispatchers.Default) {
                    com.kilombino.kilowallet.core.backup.Kab.encode(
                        com.kilombino.kilowallet.core.backup.Kab.Snapshot(words, pass, nowMs(), backupState.txs.size, settings,
                            config = arkFiles?.first?.config, db = arkFiles?.first?.db, dbWal = arkFiles?.first?.dbWal,
                            arkDerived = arkFiles?.second == true), password)
                }
                val day = kotlin.time.Clock.System.now().toString().take(10).replace("-", "")
                platform.saveFile("kilowallet-$day.kab", file) { err ->
                    when (err) {
                        null -> { say("Backup file saved. Keep it and its password apart: together they open the wallet."); go(Screen.Settings) }
                        "cancelled" -> Unit
                        else -> say("Could not save the file: $err")
                    }
                }
            }
        }
    }

    /** Pick a backup file to restore a wallet from. */
    fun pickBackupFile() {
        platform.openFile { bytes ->
            if (bytes == null) return@openFile
            if (!com.kilombino.kilowallet.core.backup.Kab.isBackup(bytes)) { say("That is not a Kilowallet backup file (.kab)."); return@openFile }
            go(Screen.RestoreFile(bytes))
        }
    }

    /** Open [bytes] with [password]: the words become the hot wallet, and the settings come back. */
    fun restoreBackup(bytes: ByteArray, password: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { com.kilombino.kilowallet.core.backup.Kab.decode(bytes, password) } }
                .onSuccess { snap ->
                    if (!Bip39.isValid(snap.words)) { say("The words in this file are not a valid backup."); return@onSuccess }
                    val id = withContext(cryptoDispatcher) { WalletCatalog.idFor(Signer.accountZpub(snap.words, snap.passphrase)) }
                    if ((snap.hasArk || snap.app?.get("lightning") != null) && !nativeEngineLease.permits(id)) {
                        say("Close and reopen the app without another active node before restoring this backup."); return@onSuccess
                    }
                    storeHot(snap.words, snap.passphrase) {
                        applySettings(snap.app)
                        if (snap.hasArk) ark.restoreFromBackup(snap)
                        else if (lightning.hasStagedChannels()) say("Wallet restored. The file has a channel backup: turning Lightning on hands it to the node, which asks each peer to close.")
                    }
                }
                .onFailure { e ->
                    say(if (e is com.kilombino.kilowallet.core.backup.Kab.WrongPassword) "Wrong password." else e.message ?: "Could not read the file.")
                }
        }
    }

    // ------------------------------------------------------------------ demo (CI screenshots only)

    /**
     * Screenshot run, real node: Lightning over Tor end to end. Waits for the watchtower to be added
     * (through Tor) and for a peer, then reaches Kilombino's node by its onion (after dropping the
     * clearnet link), and logs what LND says. Nothing moves money.
     */
    private suspend fun lightningOverTorCheck() {
        val ln = com.kilombino.kilowallet.core.ln.Lnd
        val k = com.kilombino.kilowallet.core.ln.Kilombino
        println("KW ln-live: node running, Tor in LND: ${lightning.torOn}, SOCKS ${com.kilombino.kilowallet.core.tor.Tor.port}")
        if (!lightning.torOn) return
        withTimeoutOrNull(240_000) {
            while (runCatching { ln.towers() }.getOrDefault(emptyList()).isEmpty()) { runCatching { lightning.refresh() }; delay(5_000) }
        }
        val towers = runCatching { ln.towers().toString() }.getOrElse { it.message ?: it.toString() }
        println("KW ln-live: towers $towers")
        runCatching { ln.disconnect(k.NODE) }
        delay(2_000)
        val r = runCatching { ln.connect(k.NODE, k.ONION) }
        val how = r.exceptionOrNull()?.message ?: "OK"
        val peers = runCatching { ln.peerAddresses().toString() }.getOrElse { it.message ?: it.toString() }
        println("KW ln-live: connect Kilombino by onion: $how; peers $peers")
        // RestoreChannelBackups by RPC (fixed in Lightning Fork 21.2: up to 21.1 it took every backup
        // for another chain's). The node's own backup has no channel, so this only shows the call goes through.
        val scb = runCatching { ln.channelBackup() }
        val restored = scb.mapCatching { ln.restoreChannelBackups(it); "OK" }.getOrElse { it.message ?: it.toString() }
        val scbSize = scb.getOrNull()?.size ?: 0
        println("KW ln-live: own channel backup $scbSize bytes; RestoreChannelBackups by RPC: $restored")
        runCatching { lightning.refresh() }
    }

    /**
     * Screenshot run, real Ark engine: the whole-wallet backup file end to end on iPhone. Ark's
     * files are taken with the engine stopped, sealed in a .kab, opened again, put back and the
     * engine started on them; the CI log says what came back.
     */
    private suspend fun arkBackupRoundTrip(words: List<String>) {
        val kab = com.kilombino.kilowallet.core.backup.Kab
        val r = runCatching {
            val (files, derived) = ark.backupFiles(words, "") ?: error("no Ark files to back up")
            val file = withContext(Dispatchers.Default) {
                kab.encode(com.kilombino.kilowallet.core.backup.Kab.Snapshot(words, "", nowMs(), 0, settingsJson(), config = files.config, db = files.db, dbWal = files.dbWal, arkDerived = derived), "demo password")
            }
            val back = withContext(Dispatchers.Default) { kab.decode(file, "demo password") }
            check(back.hasArk && back.db!!.contentEquals(files.db)) { "the Ark database did not come back" }
            ark.restoreFromBackup(back)
            delay(3_000)
            val v = withTimeoutOrNull(120_000) { ark.view.first { it == ArkModel.View.Ready || it is ArkModel.View.Failed } }
            "file ${file.size} bytes, db ${files.db.size} bytes, wal ${files.dbWal?.size ?: 0}, derived $derived; after restore: $v"
        }
        val how = r.getOrElse { "FAILED: " + (it.message ?: it.toString()) }
        println("KW ark-live: backup round trip: $how")
    }

    /** Throwaway state for the screenshot run in CI: the well-known "abandon … about" test wallet. */
    fun setUpDemo(screen: String) {
        val words = List(11) { "abandon" } + "about"
        when (screen) {
            "welcome" -> platform.markReady(screen)
            "create" -> { go(Screen.CreateShow(Bip39.generate(12))); platform.markReady(screen) }
            "restore" -> { go(Screen.Restore); platform.markReady(screen) }
            "watch" -> { go(Screen.Watch); platform.markReady(screen) }
            else -> scope.launch {
                val zpub = withContext(cryptoDispatcher) { Signer.accountZpub(words, "") }
                _state.update { it.copy(kind = WalletKind.HOT, zpub = zpub, walletId = WalletCatalog.LEGACY_ID,
                    wallets = listOf(WalletInfo(WalletCatalog.LEGACY_ID, "Everyday", WalletKind.HOT, zpub)), screen = Screen.Home) }
                when (screen) {
                    "wallets" -> {
                        val savings = withContext(cryptoDispatcher) { Signer.accountZpub(words, "demo-savings") }
                        val watch = withContext(cryptoDispatcher) { Signer.accountZpub(words, "demo-watch") }
                        _state.update { it.copy(wallets = it.wallets + listOf(
                            WalletInfo(WalletCatalog.idFor(savings), "Savings", WalletKind.HOT, savings),
                            WalletInfo(WalletCatalog.idFor(watch), "Watch-only", WalletKind.WATCH, watch))) }
                        go(Screen.Wallets); platform.markReady(screen)
                    }
                    "history", "private", "note", "export", "filters", "units" -> {
                        val txid = "ab".repeat(32)
                        val note = TransactionNote("Mining payout", "October test payout")
                        val notes = transactionNotes.save(zpub, txid, note.label, note.note)
                        _state.update { it.copy(
                            rows = listOf(AddressRow(0, 0, "demo", "demo", "demo", confirmed = 250_000)),
                            txs = listOf(TxConf(txid, 12, false, 250_000, height = 970_000),
                                TxConf("cd".repeat(32), 0, true, -25_000)),
                            height = 970_011, scan = ScanPhase.Done, lastSyncedAt = platform.nowMillis(),
                            notes = notes, hideAmounts = screen == "private", amountUnit = if (screen == "units") AmountUnit.XBT else AmountUnit.SATS) }
                        platform.markReady(screen)
                    }
                    "home" -> scan()
                    "immature" -> scan()
                    "receive", "request" -> { go(Screen.Receive); platform.markReady(screen) }
                    "settings" -> { go(Screen.Settings); platform.markReady(screen) }
                    "lightning" -> { selectTab(Tab.LIGHTNING); platform.markReady(screen) }
                    "ln-bridge" -> {
                        // A made-up SHA-256 invoice of 1,000 sats and a price, only so the bridge's dialog shows.
                        lightning.chainTip = 979_937
                        lightning.demoBridge = com.kilombino.kilowallet.core.ln.Lnd.PayReq("02cd566293ad8c8fd55bf5c5be38df5fc0af2b445333b10cbbd8b8a7d4c7ed7fe3",
                            "a0".repeat(32), 1000, "coffee", 86400, 1_791_978_000L, blake2b = false) to
                            com.kilombino.kilowallet.core.ln.Bridge.Estimate(1000, 118_493, 0.008622, 0.0216, 0)
                        lightning.setDemo(demoLightning("ln-running"))
                        selectTab(Tab.LIGHTNING); platform.markReady(screen)
                    }
                    "ln-move" -> { selectTab(Tab.LIGHTNING); platform.markReady(screen) }
                    "settings-notify" -> { prefs.put(BalanceWatch.KEY_ON, "1"); go(Screen.Settings); platform.markReady(screen) }
                    "ln-starting", "ln-running", "ln-failed" -> {
                        lightning.chainTip = 979_937
                        lightning.setDemo(demoLightning(screen))
                        selectTab(Tab.LIGHTNING); platform.markReady(screen)
                    }
                    "ln-live" -> {
                        // The real engine, a throwaway wallet ("abandon … about") and the password in
                        // memory: LND starts, makes its wallet and syncs against the chain's peers.
                        selectTab(Tab.LIGHTNING)
                        lightning.chainTip = 979_937
                        launch {
                            val st = lightning.status.first { it is LightningModel.Status.Running || it is LightningModel.Status.Failed }
                            if (st is LightningModel.Status.Running) lightningOverTorCheck()
                            platform.markReady(screen)
                        }
                        runCatching {
                            val seed = withContext(Dispatchers.Default) { Bip39.toSeed(words, "") }
                            withContext(Dispatchers.Default) { lightning.enable(seed) }
                        }
                    }
                    "tor-live", "tor-log" -> {
                        // The real Tor (Arti in the app): it starts and bootstraps, and with "Connect to
                        // the server through Tor" the wallet's Electrum server is reached through it.
                        launch {
                            val p = com.kilombino.kilowallet.core.tor.Tor.startAndWait(240_000)
                            val torSays = com.kilombino.kilowallet.core.tor.Tor.lastLine
                            println("KW $screen: Tor SOCKS port $p (null: not up), last line: $torSays")
                            if (screen == "tor-live" && p != null) {
                                setElectrumThroughTor(true)
                                val end = withTimeoutOrNull(180_000) { _state.first { it.scan is ScanPhase.Done || it.scan is ScanPhase.Error }.scan }
                                val height = _state.value.height
                                println("KW tor-live: Electrum through Tor: $end (null: no answer in 3 min); height $height")
                            }
                            go(if (screen == "tor-log") Screen.TorLog else Screen.Settings)
                            delay(2_500); platform.markReady(screen)
                        }
                    }
                    "ark" -> { selectTab(Tab.ARK); platform.markReady(screen) }
                    "ark-ready" -> {
                        ark.setDemo(ArkModel.View.Ready, demoArk())
                        selectTab(Tab.ARK); platform.markReady(screen)
                    }
                    "ark-live" -> {
                        // The real engine and the real server, a throwaway Ark wallet made from the
                        // "abandon … about" words: it starts, creates its wallet and hands out an
                        // address. No funds move.
                        selectTab(Tab.ARK)
                        launch {
                            val v = ark.view.first { it == ArkModel.View.Ready || it is ArkModel.View.Failed }
                            if (v == ArkModel.View.Ready) arkBackupRoundTrip(words)
                            delay(4_000); platform.markReady(screen)
                        }
                        ark.activate(words, "")
                    }
                    "simple" -> { _state.update { it.copy(simple = true) }; scan() }
                    "locked" -> { _state.update { it.copy(locked = true) }; platform.markReady(screen) }
                    "replace" -> {
                        // A made-up pending send, only so the cancel screen has something to show.
                        go(Screen.Replace("4f".repeat(32), cancel = true)); platform.markReady(screen)
                    }
                    "send" -> { go(Screen.Send); platform.markReady(screen) }
                    "backup-check" -> { go(Screen.BackupCheck); platform.markReady(screen) }
                    "recipients" -> {
                        val list = recipientsStore.save(zpub, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", "Mining account")
                        _state.update { it.copy(recipients = list) }
                        go(Screen.Settings); platform.markReady(screen)
                    }
                    "coins" -> {
                        val coins = listOf(SpendableUtxo("ab".repeat(32), 0, 250_000, 0, 0, 970000),
                            SpendableUtxo("cd".repeat(32), 1, 100_000, 0, 1, 970001))
                        val frozen = frozenStore.set(zpub, coins.last().coinId(), true)
                        _state.update { it.copy(screen = Screen.Send, spendable = coins, frozenCoins = frozen, selectedCoins = setOf(coins.first().coinId())) }
                        platform.markReady(screen)
                    }
                    "dice" -> { go(Screen.Dice); platform.markReady(screen) }
                    "psbt" -> {
                        // The same made-up coin as "review", from the demo wallet watched: the PSBT to sign elsewhere.
                        val coin = SpendableUtxo("4f".repeat(32), 0, 250_000, 0, 0, 970_000)
                        val draft = SendPlanner.plan("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 120_000, 2.0,
                            listOf(coin), SendPlanner.changeScript(zpub, 0))
                        _state.update { it.copy(kind = WalletKind.WATCH, watchKey = zpub, screen = Screen.Send,
                            send = SendPhase.Review(draft, null)) }
                        exportPsbt(); platform.markReady(screen)
                    }
                    "backup" -> { go(Screen.Backup); platform.markReady(screen) }
                    "cpfp" -> {
                        // A made-up incoming payment, only so the speed-up screen has something to show.
                        go(Screen.Cpfp("6b".repeat(32))); platform.markReady(screen)
                    }
                    "receive-taproot", "settings-taproot" -> {
                        val tpub = withContext(Dispatchers.Default) { Signer.accountTpub(words, "") }
                        _state.update { it.copy(taprootXpub = tpub, receiveTaproot = true) }
                        go(if (screen == "receive-taproot") Screen.Receive else Screen.Settings); platform.markReady(screen)
                    }
                    "pools", "pool-round", "pool-intro", "pool-history" -> {
                        withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }
                        if (screen == "pool-intro") pools.setEnabled(false)
                        go(Screen.Pools); platform.markReady(screen)
                    }
                    "pool-create" -> { withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }; go(Screen.PoolCreate); platform.markReady(screen) }
                    "pool-join" -> {
                        withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }
                        pools.state.value.open?.firstOrNull()?.let { go(Screen.PoolJoin(it.id)) }; platform.markReady(screen)
                    }
                    "pool-pay" -> { withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }; go(Screen.PoolPay); platform.markReady(screen) }
                    "review" -> {
                        // A made-up coin, only so the review screen has something to show.
                        val coin = SpendableUtxo("4f".repeat(32), 0, 250_000, 0, 0, 970_000)
                        val draft = SendPlanner.plan("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 120_000, 2.0,
                            listOf(coin), SendPlanner.changeScript(zpub, 0))
                        _state.update { it.copy(screen = Screen.Send, send = SendPhase.Review(draft, SendPlanner.feeConcern(draft), 0)) }
                        platform.markReady(screen)
                    }
                }
            }
        }
    }
}

/** Screenshot demos of the LIGHTNING tab: made-up figures, never a real node. */
/** Made-up Ark figures for the "ark-ready" screenshot. */
private fun demoArk(): ArkModel.Figures = ArkModel.Figures(
    balance = com.kilombino.kilowallet.core.ark.Ark.Balance(
        spendable = 184_250, pendingBoard = 40_000, pendingRound = 0, needsRefresh = 0, pendingExit = 0,
        onchainConfirmed = 12_500, onchainPending = 0, lightningPending = 0,
    ),
    expiry = 3_870,
    activity = listOf(
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-1", "pending", "move into Ark", 40_000, "2026-11-01T10:12:00", 2_000,
            onchainTxid = "6b".repeat(32), confirmations = 1),
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-2", "successful", "Lightning payment", -12_480, "2026-10-31T18:40:00", 4_120),
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-3", "successful", "Ark received", 25_000, "2026-10-30T09:05:00"),
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-4", "successful", "renewal", -1_056, "2026-10-28T21:00:00", 1_056),
    ),
)

private fun demoLightning(screen: String): LightningModel.Status = when (screen) {
    "ln-starting" -> LightningModel.Status.Starting("catching up with the chain", 812_450)
    "ln-failed" -> LightningModel.Status.Failed("The node stopped: unable to connect to any peer")
    else -> {
        val k = com.kilombino.kilowallet.core.ln.Kilombino.NODE
        LightningModel.Status.Running(
            com.kilombino.kilowallet.core.ln.Lnd.Info("02" + "7a".repeat(32), "Kilowallet-demo", 979_937, true, true, 1, 1, 0, 1_793_480_000L),
            com.kilombino.kilowallet.core.ln.Lnd.Balances(48_200, 0, 412_350, 0),
            listOf(com.kilombino.kilowallet.core.ln.Lnd.Channel(true, k, "4f".repeat(32) + ":0", 1_000_000, 412_350, 584_650, "Kilombino", 1L)),
            emptyList(), 979_937,
            invoices = listOf(com.kilombino.kilowallet.core.ln.Lnd.Invoice("coffee", 2_100, 2_100, 1_793_470_000L, 1_793_470_060L, 1)),
            payments = listOf(com.kilombino.kilowallet.core.ln.Lnd.Payment(15_000, 3, 1_793_475_000L, 2, "ab".repeat(32))),
        )
    }
}

/** Words and passphrase in one Keychain item: "word word …\npassphrase". */
internal fun encodeSecret(words: List<String>, passphrase: String) = words.joinToString(" ") + "\n" + passphrase
internal fun decodeSecret(s: String): Pair<List<String>, String> {
    val nl = s.indexOf('\n')
    val w = if (nl < 0) s else s.substring(0, nl)
    return w.split(' ').filter { it.isNotEmpty() } to (if (nl < 0) "" else s.substring(nl + 1))
}

/** A payment URI or bare address. Throws for incompatible/ambiguous requests. */
fun parsePaymentText(text: String) = com.kilombino.kilowallet.core.wallet.PaymentRequest.parse(text)
fun btcToSats(s: String) = com.kilombino.kilowallet.core.wallet.PaymentRequest.toSats(s)
