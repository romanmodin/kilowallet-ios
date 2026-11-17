package com.kilombino.kilowallet.core.chain

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Base58
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToLong

/**
 * Your own Bitcoin node (Knots with BLAKE2b) by RPC instead of an Electrum server, ported from the
 * Android app's `chain/NodeRpc.kt` (Kilowallet 1.6.0): same connection strings, same checks, same
 * watch-only wallet on the node. The HTTP requests go over the platform's [TransportFactory]
 * (Network.framework on iOS: a .onion through the app's Tor, https pinned to the certificate the
 * user confirmed), as the Android app's plain socket and pinned HttpsURLConnection.
 */

/** A parsed RPC address (no java.net.URL in common code). */
internal data class RpcUrl(val scheme: String, val user: String, val pass: String, val host: String, val port: Int, val path: String) {
    companion object {
        fun parse(text: String): RpcUrl {
            val t = text.trim()
            val scheme = t.substringBefore("://", "").lowercase()
            require(scheme == "http" || scheme == "https") { "Only http:// or https:// addresses." }
            var rest = t.substringAfter("://")
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val path = rest.substring(slash).ifEmpty { "/" }
            rest = rest.substring(0, slash)
            val at = rest.lastIndexOf('@')
            val userInfo = if (at >= 0) rest.substring(0, at) else ""
            val hostPort = if (at >= 0) rest.substring(at + 1) else rest
            val host: String
            val portText: String?
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                require(close > 0) { "Bad IPv6 address." }
                host = hostPort.substring(0, close + 1)
                portText = hostPort.substring(close + 1).removePrefix(":").ifEmpty { null }
            } else {
                host = hostPort.substringBefore(':')
                portText = if (hostPort.contains(':')) hostPort.substringAfter(':') else null
            }
            require(host.isNotEmpty() && host != "[]") { "No host in that address." }
            val port = portText?.let { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Bad port: $p") } ?: -1
            val user = userInfo.substringBefore(':')
            val pass = if (userInfo.contains(':')) userInfo.substringAfter(':') else ""
            return RpcUrl(scheme, percentDecode(user), percentDecode(pass), host, port, path)
        }

        private fun percentDecode(s: String): String {
            if (!s.contains('%') && !s.contains('+')) return s
            val out = ArrayList<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out += v.toByte(); i += 3; continue }
                }
                if (c == '+') { out += ' '.code.toByte(); i++; continue }
                c.toString().encodeToByteArray().forEach { out += it }
                i++
            }
            return out.toByteArray().decodeToString()
        }
    }
}

/** One way to reach the node's RPC: a URL (http://host:port, or a .onion through Tor) and its login. */
/**
 * [pin]: for https (a StartOS node serves its RPC over TLS with its own certificate authority),
 * the SHA-256 of the certificate trusted when the connection was added; any other is refused.
 */
data class RpcConn(val url: String, val user: String, val pass: String, val pin: String = "") {
    val host: String get() = runCatching { RpcUrl.parse(url).host }.getOrDefault("")
    val isOnion: Boolean get() = isOnionHost(host)
    val isTls: Boolean get() = url.startsWith("https://", ignoreCase = true)

    fun toJson(): JsonObject = buildJsonObject { put("url", url); put("user", user); put("pass", pass); put("pin", pin) }

    companion object {
        fun fromJson(o: JsonObject) = RpcConn(
            o["url"]!!.jsonPrimitive.content, o["user"]?.jsonPrimitive?.contentOrNull ?: "",
            o["pass"]?.jsonPrimitive?.contentOrNull ?: "", o["pin"]?.jsonPrimitive?.contentOrNull ?: "",
        )

        fun listToJson(list: List<RpcConn>): String = JsonArray(list.map { it.toJson() }).toString()
        fun listFromJson(json: String?): List<RpcConn> = runCatching {
            Json.parseToJsonElement(json ?: "[]").jsonArray.map { fromJson(it.jsonObject) }
        }.getOrDefault(emptyList())

        /** A host on this network (private or link-local address, localhost, a one-word or .local name) or a .onion. */
        fun localOrOnion(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h.endsWith(".onion") || h == "localhost" || !h.contains('.') && !h.contains(':')) return true
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            val v4 = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
            if (v4 != null) return v4[0] == 10 || v4[0] == 127 || (v4[0] == 192 && v4[1] == 168) ||
                (v4[0] == 172 && v4[1] in 16..31) || (v4[0] == 169 && v4[1] == 254) || (v4[0] == 100 && v4[1] in 64..127)
            if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
            return false
        }

        /**
         * A connection from what a QR or a paste gives: `http://user:pass@host:port`,
         * `btcrpc://user:pass@host:port`, `user:pass@host:port`, or plain `host:port`.
         */
        fun parse(text: String): RpcConn {
            var t = text.trim()
            t = t.replaceFirst(Regex("^(btcrpc|bitcoin-rpc|rpc)://", RegexOption.IGNORE_CASE), "http://")
            if (!t.contains("://")) t = "http://$t"
            val u = RpcUrl.parse(t)
            val port = if (u.port > 0) u.port else 8332
            // Plain http carries the password and the node's answers in clear: only to this
            // network or a .onion. Anything farther has to be https (pinned) or .onion.
            require(u.scheme != "http" || localOrOnion(u.host)) {
                "Plain http only to your own network (192.168.x.x, 10.x.x.x, a .local name…) or a .onion address. From elsewhere use https."
            }
            return RpcConn("${u.scheme}://${u.host}:$port/", u.user, u.pass)
        }
    }
}

/** Reading the chain from the user's own node by RPC: its connections and the wallet's account key. */
data class RpcNode(val conns: List<RpcConn>, val accountKey: String, val type: ScriptType)

/**
 * The Electrum questions the wallet asks (per-address history, balance, coins), answered by the
 * user's own node instead of an Electrum server. The node gets a watch-only descriptor wallet
 * for the account (public key only), which tracks history and the mempool; on connect everything
 * is read once and then served from memory. The keys never reach the node.
 */
class NodeRpcBackend(private val node: RpcNode, private val transports: TransportFactory) {
    private var conn: RpcConn? = null
    private val descKey by lazy { toVersion(node.accountKey, 0x0488B21E) }
    /** One node wallet per key AND address type (Android 1.5.x): native SegWit keeps its old name. */
    val walletName by lazy {
        val tag = if (node.type == ScriptType.P2WPKH) "" else ":" + node.type.name
        "kilowallet-" + Hashes.sha256((descKey + tag).encodeToByteArray()).toHex().take(10)
    }

    private val history = HashMap<String, MutableSet<Pair<String, Int>>>()
    /** TLS: the certificate's fingerprint the last request saw. */
    var lastFingerprint: String? = null
        private set
    private var testing = false
    private val utxos = HashMap<String, MutableList<ElectrumClient.Utxo>>()

    class NodeException(message: String, val rpcCode: Int? = null) : ElectrumException(message)

    @OptIn(ExperimentalEncodingApi::class)
    private fun call(c: RpcConn, method: String, vararg params: Any?, wallet: String? = null, timeoutSeconds: Int = 60): JsonElement {
        val u = RpcUrl.parse(c.url)
        val path = if (wallet == null) u.path else u.path.trimEnd('/') + "/wallet/" + urlEncode(wallet)
        if (c.isTls && c.pin.isEmpty() && !testing) throw NodeException("${c.url} has no trusted certificate yet: remove it and add it again.")
        val body = buildJsonObject {
            put("jsonrpc", "1.0"); put("id", "kw"); put("method", method)
            put("params", JsonArray(params.map { toJson(it) }))
        }.toString()
        val auth = "Basic " + Base64.Default.encode("${c.user}:${c.pass}".encodeToByteArray())
        val port = if (u.port > 0) u.port else if (c.isTls) 443 else 80
        val t = try {
            transports.openRpc(u.host.trim('[', ']'), port, c.isTls, c.pin.ifEmpty { null }, timeoutSeconds)
        } catch (e: CertificateChangedException) {
            throw NodeException("The certificate of ${u.host} has CHANGED (now ${e.fingerprint.take(16)}…); nothing was sent. " +
                "If you changed it, remove the connection and add it again.")
        } catch (e: ElectrumException) {
            // Through Tor, "connection refused" means the .onion was reached but nothing listens on that port.
            if (c.isOnion && e.message?.contains("refused", true) == true)
                throw NodeException("The .onion was reached, but nothing answers on port $port. Copy the full address, port included, from your node's RPC interface.")
            throw NodeException("Could not reach ${c.url}: ${e.message}")
        }
        val (code, text) = try {
            lastFingerprint = t.fingerprint
            httpPost(t, u.host, port, path, auth, body)
        } finally { runCatching { t.close() } }
        if (code == 401 || code == 403) throw NodeException("The node refused the RPC login (user or password).")
        if (text.isBlank()) throw NodeException("HTTP $code from the node")
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw NodeException("${c.url} answered (HTTP $code), but it is not a Bitcoin node's RPC.")
        }
        val err = o["error"]
        if (err != null && err !is JsonNull) throw NodeException(
            (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString(),
            (err as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull)
        return o["result"] ?: JsonNull
    }

    private fun call(method: String, vararg params: Any?, wallet: String? = null, timeoutSeconds: Int = 60): JsonElement =
        call(conn ?: throw NodeException("not connected"), method, *params, wallet = wallet, timeoutSeconds = timeoutSeconds)

    /** One node-level RPC call on the connection [connect] chose. */
    fun rpc(method: String, params: List<Any?>): JsonElement = call(method, *params.toTypedArray())

    private fun desc(branch: Int): String = when (node.type) {
        ScriptType.P2WPKH -> "wpkh($descKey/$branch/*)"
        ScriptType.P2SH_P2WPKH -> "sh(wpkh($descKey/$branch/*))"
        ScriptType.P2PKH -> "pkh($descKey/$branch/*)"
        ScriptType.P2TR -> "tr($descKey/$branch/*)"
    }

    /** "Bitcoin node 29.x (RPC)" for the status line. */
    var version: String = "your node (RPC)"
        private set

    /**
     * The first connection that answers, on the BLAKE2b chain; then the node's watch-only wallet
     * (made the first time, looking back from the oldest coin the account still holds) and
     * wallet data is loaded lazily only when a scan needs it.
     */
    fun connect(progress: (String) -> Unit = {}) {
        connectChain()
        walletProgress = progress
    }

    private var walletProgress: (String) -> Unit = {}
    private var walletReady = false
    private var snapshotReady = false

    private fun requireWallet() {
        if (!walletReady) { ensureWallet(walletProgress); walletReady = true }
    }

    private fun requireSnapshot() {
        if (!snapshotReady) { requireWallet(); load(); snapshotReady = true }
    }

    /** Only the first connection that answers, checked to be on the BLAKE2b chain. */
    fun connectChain() {
        var last: Exception? = null
        for (c in node.conns) {
            try {
                val dep = call(c, "getdeploymentinfo").jsonObject
                if (!isBlake(dep)) throw NodeException("${c.url} is not on the BLAKE2b chain.")
                conn = c
                version = (call(c, "getnetworkinfo").jsonObject["subversion"]?.jsonPrimitive?.contentOrNull ?: "").trim('/') + " (RPC)"
                break
            } catch (e: Exception) { last = e }
        }
        if (conn == null) throw (last as? ElectrumException ?: NodeException(last?.message ?: "No connection to the node answered."))
    }

    private fun ensureWallet(progress: (String) -> Unit) {
        val loaded = call("listwallets").jsonArray.map { it.jsonPrimitive.content }
        if (walletName in loaded) return
        if (runCatching { call("loadwallet", walletName) }.isSuccess) return
        progress("first time on this node: looking for this wallet's coins (a few minutes)")
        val since = autoSince()
        call("createwallet", walletName, true, true, "", false, true, true)
        val req = buildJsonArray {
            for (b in 0..1) {
                val d = call("getdescriptorinfo", desc(b)).jsonObject["descriptor"]!!.jsonPrimitive.content
                add(buildJsonObject {
                    put("desc", d); put("range", buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(999)) })
                    if (since != null) put("timestamp", since) else put("timestamp", "now")
                    put("internal", b == 1); put("active", false)
                })
            }
        }
        progress("the node is reading this wallet's history")
        call("importdescriptors", req, wallet = walletName, timeoutSeconds = 3600)
    }

    /** Where the history starts: the oldest coin still held, never before what a pruned node keeps. */
    private fun autoSince(): Long? {
        val d = buildJsonArray {
            for (b in 0..1) add(buildJsonObject { put("desc", desc(b)); put("range", 999) })
        }
        val r = call("scantxoutset", "start", d, timeoutSeconds = 1800).jsonObject
        val u = r["unspents"]?.jsonArray ?: return null
        val minH = u.mapNotNull { it.jsonObject["height"]?.jsonPrimitive?.intOrNull }.filter { it > 0 }.minOrNull() ?: return null
        val info = call("getblockchaininfo").jsonObject
        val pruned = info["pruned"]?.jsonPrimitive?.booleanOrNull == true
        val from = maxOf(minH - 6, if (pruned) info["pruneheight"]?.jsonPrimitive?.intOrNull ?: 0 else 0)
        val hash = call("getblockhash", from).jsonPrimitive.content
        return (call("getblockheader", hash).jsonObject["time"]!!.jsonPrimitive.longOrNull ?: 0L) - 7200
    }

    private fun sats(btc: Double): Long = (btc * 1e8).roundToLong()
    private fun dbl(e: JsonElement?): Double = e?.jsonPrimitive?.doubleOrNull ?: 0.0
    private fun int(e: JsonElement?): Int = e?.jsonPrimitive?.intOrNull ?: 0
    private fun str(e: JsonElement?): String = e?.jsonPrimitive?.contentOrNull ?: ""

    /** Everything the wallet's addresses did, read from the node's wallet into memory. */
    private fun load() {
        history.clear(); utxos.clear(); tipCache = -1
        val ids = LinkedHashSet<String>()
        val lt = call("listtransactions", "*", 10_000, 0, true, wallet = walletName).jsonArray
        for (e in lt) ids += str(e.jsonObject["txid"])
        val lu = call("listunspent", 0, 9_999_999, JsonArray(emptyList()), true, wallet = walletName).jsonArray
        for (e in lu) {
            val o = e.jsonObject
            ids += str(o["txid"])
            val sh = Address.electrumScriptHash(Hashes.hexToBytes(str(o["scriptPubKey"])))
            val conf = int(o["confirmations"])
            val h = if (conf > 0) tip() - conf + 1 else 0
            utxos.getOrPut(sh) { mutableListOf() } += ElectrumClient.Utxo(str(o["txid"]), int(o["vout"]), sats(dbl(o["amount"])), h)
        }
        val outScripts = HashMap<String, String>() // "txid:vout" -> scripthash, for inputs
        // A payment to ourselves (a CPFP, a consolidation) never shows in listtransactions: found
        // through the inputs of whatever later spent it (Android 1.5.x).
        val queue = ArrayDeque(ids)
        val decodedMap = LinkedHashMap<String, JsonObject>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in decodedMap) continue
            val t = wtx(id) ?: continue
            decodedMap[id] = t
            val vin = t["decoded"]?.jsonObject?.get("vin")?.jsonArray ?: continue
            for (i in vin) str(i.jsonObject["txid"]).takeIf { it.length == 64 && it !in decodedMap }?.let { queue += it }
        }
        for ((id, t) in decodedMap) {
            for (o in t["decoded"]!!.jsonObject["vout"]!!.jsonArray) {
                val ob = o.jsonObject
                outScripts["$id:${int(ob["n"])}"] = Address.electrumScriptHash(Hashes.hexToBytes(str(ob["scriptPubKey"]!!.jsonObject["hex"])))
            }
        }
        // A mined reward still maturing: listunspent leaves it out, an Electrum server counts it.
        for (e in lt) {
            val o = e.jsonObject
            if (str(o["category"]) != "immature") continue
            val id = str(o["txid"]); val n = o["vout"]?.jsonPrimitive?.intOrNull ?: -1
            val conf = int(o["confirmations"])
            val sh = outScripts["$id:$n"] ?: continue
            if (conf <= 0) continue
            val list = utxos.getOrPut(sh) { mutableListOf() }
            if (list.none { it.txid == id && it.vout == n }) list += ElectrumClient.Utxo(id, n, sats(dbl(o["amount"])), tip() - conf + 1)
        }
        for ((id, t) in decodedMap) {
            val conf = t["confirmations"]?.jsonPrimitive?.intOrNull ?: 0
            if (conf < 0) continue
            if (conf == 0) {
                try { call("getmempoolentry", id) }
                catch (e: NodeException) { if (e.rpcCode == -5) continue else throw e }
            }
            val h = if (conf > 0) int(t["blockheight"]) else 0
            val d = t["decoded"]!!.jsonObject
            val touched = HashSet<String>()
            for (o in d["vout"]!!.jsonArray) outScripts["$id:${int(o.jsonObject["n"])}"]?.let { touched += it }
            for (i in d["vin"]!!.jsonArray) outScripts["${str(i.jsonObject["txid"])}:${int(i.jsonObject["vout"])}"]?.let { touched += it }
            for (sh in touched) history.getOrPut(sh) { mutableSetOf() } += id to h
        }
    }

    private var tipCache = -1
    private fun tip(): Int { if (tipCache < 0) tipCache = call("getblockcount").jsonPrimitive.intOrNull ?: 0; return tipCache }

    /** Always refresh mutable confirmations and ownership; transport errors must fail the read. */
    internal fun wtx(txid: String): JsonObject? = try {
        call("gettransaction", txid, true, true, wallet = walletName).jsonObject
    } catch (e: NodeException) {
        if (e.rpcCode == -5) null else throw e
    }

    fun blockHeight(): Int = tip()

    /** A block's header (hex) from the node: it validated the block itself. */
    fun blockHeader(height: Int): String = call("getblockheader", call("getblockhash", height).jsonPrimitive.content, false).jsonPrimitive.content

    fun balance(sh: String): ScriptHashBalance {
        requireSnapshot()
        val u = utxos[sh].orEmpty()
        return ScriptHashBalance(u.filter { it.height > 0 }.sumOf { it.value }, u.filter { it.height <= 0 }.sumOf { it.value })
    }

    fun status(sh: String): String? {
        requireSnapshot()
        val h = history[sh] ?: return null
        if (h.isEmpty()) return null
        // The coins held go in too, so a balance read differently (maturing rewards) changes the status.
        val coins = balance(sh).let { "|${it.confirmed}:${it.unconfirmed}" }
        return Hashes.sha256((h.sortedBy { it.first }.joinToString("") { "${it.first}:${it.second}:" } + coins).encodeToByteArray()).toHex()
    }

    fun history(sh: String): List<ElectrumClient.HistoryItem> {
        requireSnapshot()
        return history[sh].orEmpty().map { ElectrumClient.HistoryItem(it.first, it.second) }.sortedBy { if (it.height <= 0) Int.MAX_VALUE else it.height }
    }

    fun listUnspent(sh: String): List<ElectrumClient.Utxo> {
        requireSnapshot()
        return utxos[sh].orEmpty()
    }

    fun estimateFeePerKb(blocks: Int): Double = runCatching {
        call("estimatesmartfee", blocks).jsonObject["feerate"]?.jsonPrimitive?.doubleOrNull ?: -1.0
    }.getOrDefault(-1.0)

    fun relayFeePerKb(): Double = runCatching { dbl(call("getnetworkinfo").jsonObject["relayfee"]) }.getOrDefault(0.0)

    fun transaction(txid: String): String {
        requireWallet()
        val hex = wtx(txid)?.get("hex")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: runCatching { call("getrawtransaction", txid).jsonPrimitive.contentOrNull }.getOrNull()
            ?: throw NodeException("The node does not know $txid (it needs txindex for others' transactions).")
        // As for an Electrum server: the transaction must hash to the id asked for.
        val got = runCatching { TxParse.txid(TxParse.parse(hex)) }.getOrNull()
        if (got != txid.lowercase()) throw NodeException("The node answered another transaction for $txid.")
        return hex
    }

    fun broadcast(rawTxHex: String): String = call("sendrawtransaction", rawTxHex).jsonPrimitive.content

    companion object {

        internal fun isBlake(dep: JsonObject): Boolean {
            fun active(o: JsonElement?) = (o as? JsonObject)?.get("active")?.jsonPrimitive?.booleanOrNull == true
            val d = dep["deployments"] as? JsonObject
            return active(dep["blake2b"]) || active(dep["hardfork"]) || active(d?.get("blake2b"))
        }

        fun toVersion(key: String, v: Int): String {
            val raw = Base58.decodeChecked(key)
            raw[0] = (v ushr 24).toByte(); raw[1] = (v ushr 16).toByte(); raw[2] = (v ushr 8).toByte(); raw[3] = v.toByte()
            return Base58.encodeChecked(raw)
        }

        /** A fingerprint as it is easiest to compare by eye: groups of four. */
        fun spaced(fp: String): String = fp.uppercase().chunked(4).joinToString(" ")

        /**
         * The certificate an https node presents, from the TLS handshake alone: nothing is sent, so
         * the RPC password goes nowhere before the user has compared this fingerprint with the one
         * the node shows (StartOS: the interface's certificate) and trusted it.
         */
        fun probeCertificate(c: RpcConn, transports: TransportFactory): String {
            val u = RpcUrl.parse(c.url)
            val port = if (u.port > 0) u.port else 443
            val t = try { transports.openRpc(u.host.trim('[', ']'), port, true, null, 30) } catch (e: ElectrumException) {
                throw NodeException("Could not reach ${c.url}: ${e.message}")
            }
            try { return t.fingerprint ?: throw NodeException("${c.url} showed no certificate.") } finally { runCatching { t.close() } }
        }

        /**
         * Logs in and reads what the node is. An https connection must carry the pin the user
         * confirmed ([probeCertificate] first): the password is sent only to that certificate.
         */
        fun test(c: RpcConn, transports: TransportFactory): String {
            if (c.isTls && c.pin.isEmpty()) throw NodeException("Check the node's certificate first.")
            val b = NodeRpcBackend(RpcNode(listOf(c), "", ScriptType.P2WPKH), transports)
            val info = b.call(c, "getblockchaininfo").jsonObject
            val dep = b.call(c, "getdeploymentinfo").jsonObject
            val v = (b.call(c, "getnetworkinfo").jsonObject["subversion"]?.jsonPrimitive?.contentOrNull ?: "").trim('/')
            return "$v · block ${b.int(info["blocks"])}" + if (isBlake(dep)) " · BLAKE2b ✓" else " · NOT on BLAKE2b"
        }

        private fun toJson(p: Any?): JsonElement = when (p) {
            null -> JsonNull
            is JsonElement -> p
            is String -> JsonPrimitive(p)
            is Int -> JsonPrimitive(p)
            is Long -> JsonPrimitive(p)
            is Boolean -> JsonPrimitive(p)
            is Double -> JsonPrimitive(p)
            else -> JsonPrimitive(p.toString())
        }

        private fun urlEncode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code)
                    append(c.toChar())
                else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
            }
        }

        /**
         * One HTTP/1.1 POST. Outbound requests include a trailing newline, counted in
         * Content-Length. Responses use raw reads so chunk boundaries never alter UTF-8 data.
         */
        internal fun httpPost(t: LineTransport, host: String, port: Int, path: String, auth: String, body: String): Pair<Int, String> {
            val len = body.encodeToByteArray().size + 1
            val head = "POST $path HTTP/1.1\r\nHost: $host:$port\r\nAuthorization: $auth\r\n" +
                "Content-Type: application/json\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
            try { t.writeLine(head + body) } catch (e: Exception) { throw NodeException("Lost the connection to the node: ${e.message}") }
            return readHttp { t.readBytes(it) }
        }

        /** Parse HTTP framing as bytes; decode UTF-8 only after all chunks are joined. */
        internal fun readHttp(next: (Int) -> ByteArray?): Pair<Int, String> {
            val limit = 8 * 1024 * 1024
            var buffer = ByteArray(0)
            var at = 0
            fun byte(): Int? {
                if (at == buffer.size) {
                    buffer = next(8192) ?: return null
                    if (buffer.isEmpty()) throw NodeException("Empty HTTP read")
                    at = 0
                }
                return buffer[at++].toInt() and 255
            }
            var headerBytes = 0
            fun line(): String {
                val out = StringBuilder()
                while (true) {
                    val b = byte() ?: throw NodeException("Truncated HTTP framing")
                    if (++headerBytes > 65536) throw NodeException("HTTP headers too large")
                    if (b == 10) {
                        if (!out.endsWith("\r")) throw NodeException("Invalid HTTP line ending")
                        return out.dropLast(1).toString()
                    }
                    out.append(b.toChar())
                }
            }
            val status = line().split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw NodeException("No HTTP answer from the node")
            var length: Int? = null
            var chunked = false
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                val k = h.substringBefore(':').trim().lowercase()
                val v = h.substringAfter(':', "").trim()
                if (k == "content-length") {
                    val n = v.toIntOrNull()?.takeIf { it in 0..limit } ?: throw NodeException("Invalid HTTP length")
                    if (length != null && length != n) throw NodeException("Conflicting HTTP lengths")
                    length = n
                }
                if (k == "transfer-encoding") {
                    if (!v.equals("chunked", true)) throw NodeException("Unsupported HTTP transfer encoding")
                    chunked = true
                }
            }
            if (chunked && length != null) throw NodeException("Ambiguous HTTP framing")
            val parts = ArrayList<ByteArray>()
            var total = 0
            fun read(n: Int) {
                if (n < 0 || n > limit - total) throw NodeException("HTTP body too large")
                val part = ByteArray(n)
                for (i in part.indices) part[i] = (byte() ?: throw NodeException("Truncated HTTP body")).toByte()
                total += n
                parts += part
            }
            if (chunked) {
                while (true) {
                    val n = line().substringBefore(';').trim().toIntOrNull(16)
                        ?: throw NodeException("Invalid HTTP chunk size")
                    if (n == 0) {
                        while (line().isNotEmpty()) { /* bounded trailers */ }
                        break
                    }
                    read(n)
                    if (byte() != 13 || byte() != 10) throw NodeException("Invalid HTTP chunk delimiter")
                }
            } else if (length != null) read(length)
            else {
                var eof = false
                while (!eof) {
                    val part = ByteArray(8192)
                    var n = 0
                    while (n < part.size) {
                        val b = byte()
                        if (b == null) { eof = true; break }
                        if (total + n == limit) throw NodeException("HTTP body too large")
                        part[n++] = b.toByte()
                    }
                    if (n > 0) { parts += part.copyOf(n); total += n }
                }
            }
            val body = ByteArray(total)
            var offset = 0
            for (part in parts) { part.copyInto(body, offset); offset += part.size }
            return status to body.decodeToString(throwOnInvalidSequence = true).trim()
        }
    }
}
