package com.kilombino.kilowallet.core.chain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.*
import kotlinx.serialization.json.*

/** Your own node by RPC (Android 1.6.0's NodeRpcTest, same connection texts) and its HTTP over lines. */
class NodeRpcTest {
    @Test fun parsesConnectionTextsAsAndroid() {
        assertEquals(RpcConn("http://10.0.0.5:8332/", "u", "p:x"), RpcConn.parse("btcrpc://u:p%3Ax@10.0.0.5:8332"))
        assertEquals(RpcConn("http://abc.onion:8332/", "", ""), RpcConn.parse("abc.onion"))
        assertTrue(RpcConn.parse("http://a:b@xyz.onion:18443").isOnion)
        // Plain http only to this network or a .onion; https anywhere.
        assertTrue(runCatching { RpcConn.parse("http://u:p@203.0.113.5:8332") }.isFailure)
        assertTrue(runCatching { RpcConn.parse("http://u:p@node.example.com:8332") }.isFailure)
        assertTrue(runCatching { RpcConn.parse("https://u:p@node.example.com:8332") }.isSuccess)
        assertTrue(runCatching { RpcConn.parse("http://u:p@192.168.1.20:8332") }.isSuccess)
        assertTrue(runCatching { RpcConn.parse("http://u:p@umbrel:8332") }.isSuccess)
        assertTrue(runCatching { RpcConn.parse("http://u:p@start9.local:8332") }.isSuccess)
        // What a node's QR code carries: user:pass@host:port, with or without a scheme.
        assertEquals(RpcConn("http://192.168.1.20:8332/", "kw", "s3cr3t"), RpcConn.parse("kw:s3cr3t@192.168.1.20"))
        assertEquals(RpcConn("https://node.example.com:443/", "a", "b"), RpcConn.parse("https://a:b@node.example.com:443"))
        assertEquals(RpcConn("http://[fd00::5]:8332/", "", ""), RpcConn.parse("http://[fd00::5]:8332"))
    }

    @Test fun keepsTheListAcrossSaves() {
        val l = listOf(RpcConn("http://10.0.0.5:8332/", "u", "p"), RpcConn("https://n.example:443/", "a", "b", "ab12"))
        assertEquals(l, RpcConn.listFromJson(RpcConn.listToJson(l)))
        assertEquals(emptyList(), RpcConn.listFromJson(null))
    }

    @Test fun readsHttpAnswersPlainAndChunked() {
        fun read(vararg lines: String): Pair<Int, String> = response(lines.joinToString("\n", postfix = "\n").encodeToByteArray())
        val body = """{"result":5,"error":null,"id":"kw"}"""
        assertEquals(200 to body, read("HTTP/1.1 200 OK\r", "Content-Type: application/json\r", "Content-Length: ${body.length + 1}\r", "\r", body))
        assertEquals(401 to "", read("HTTP/1.1 401 Unauthorized\r", "Content-Length: 0\r", "\r"))
        // A proxy in front of the node (StartOS) may chunk the answer.
        val hex = (body.length + 1).toString(16)
        assertEquals(200 to body, read("HTTP/1.1 200 OK\r", "Transfer-Encoding: chunked\r", "\r", "$hex\r", body, "\r", "0\r", "\r"))
    }

    @Test fun answersFromTheNodeAreCheckedForTheBlake2bChain() {
        val active = kotlinx.serialization.json.Json.parseToJsonElement("""{"deployments":{"blake2b":{"active":true}}}""").let { it as kotlinx.serialization.json.JsonObject }
        val not = kotlinx.serialization.json.Json.parseToJsonElement("""{"deployments":{"taproot":{"active":true}}}""").let { it as kotlinx.serialization.json.JsonObject }
        assertTrue(NodeRpcBackend.isBlake(active))
        assertTrue(!NodeRpcBackend.isBlake(not))
    }

    private fun response(bytes: ByteArray, fragment: Int = 7): Pair<Int, String> {
        var offset = 0
        return NodeRpcBackend.readHttp { max ->
            if (offset == bytes.size) null else {
                val end = minOf(bytes.size, offset + fragment, offset + max)
                bytes.copyOfRange(offset, end).also { offset = end }
            }
        }
    }

    @Test fun chunkBoundariesIncludingUtf8NeverAlterTheBody() {
        val body = """{"result":"first café 中 last","error":null,"id":"kw"}"""
        val bytes = body.encodeToByteArray()
        for (split in 1 until bytes.size) {
            val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".encodeToByteArray() +
                (split.toString(16) + ";test=yes\r\n").encodeToByteArray() + bytes.copyOfRange(0, split) +
                ("\r\n" + (bytes.size - split).toString(16) + "\r\n").encodeToByteArray() +
                bytes.copyOfRange(split, bytes.size) + "\r\n0\r\nX-Test: yes\r\n\r\n".encodeToByteArray()
            for (fragment in listOf(1, 3, 8192)) assertEquals(200 to body, response(wire, fragment), "split=$split fragment=$fragment")
        }
        assertEquals(200 to body, response(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n").encodeToByteArray()+bytes))
    }

    @Test fun invalidAndTruncatedHttpFailsClosed() {
        for (wire in listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabc",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nab",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 9000000\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
        )) assertFailsWith<NodeRpcBackend.NodeException> { response(wire.encodeToByteArray()) }
    }

    private fun backend(answer: () -> JsonObject): NodeRpcBackend {
        val transport = TransportFactory { _, _, _, _ ->
            object : LineTransport {
                override val fingerprint: String? = null
                var bytes = ByteArray(0)
                var offset = 0
                override fun writeLine(line: String) {
                    val method = Json.parseToJsonElement(line.substringAfter("\r\n\r\n")).jsonObject["method"]!!.jsonPrimitive.content
                    val result = when(method) {
                        "getdeploymentinfo" -> Json.parseToJsonElement("""{"result":{"deployments":{"blake2b":{"active":true}}},"error":null}""").jsonObject
                        "getnetworkinfo" -> Json.parseToJsonElement("""{"result":{"subversion":"test"},"error":null}""").jsonObject
                        "gettransaction" -> answer()
                        else -> error("Unexpected mock RPC $method")
                    }.toString().encodeToByteArray()
                    bytes = "HTTP/1.1 200 OK\r\nContent-Length: ${result.size}\r\n\r\n".encodeToByteArray() + result
                }
                override fun readLine(): String? = error("RPC must read bytes")
                override fun readBytes(maxBytes: Int): ByteArray? {
                    if (offset == bytes.size) return null
                    val end = minOf(bytes.size, offset + maxBytes)
                    return bytes.copyOfRange(offset,end).also { offset = end }
                }
                override fun close() {}
            }
        }
        val key = com.kilombino.kilowallet.core.wallet.Signer.accountZpub(List(11) { "abandon" } + "about", "")
        return NodeRpcBackend(RpcNode(listOf(RpcConn.parse("http://127.0.0.1:8332")),key,
            com.kilombino.kilowallet.core.crypto.ScriptType.P2WPKH),transport).also { it.connectChain() }
    }

    @Test fun transientTransactionFailuresDoNotHideLaterAnswersOrOtherBackends() {
        var calls = 0
        val good = Json.parseToJsonElement("""{"result":{"confirmations":8,"hex":"synthetic"},"error":null}""").jsonObject
        val b = backend { if (++calls == 1) throw ElectrumException("synthetic timeout") else good }
        assertFailsWith<NodeRpcBackend.NodeException> { b.wtx("synthetic-id") }
        assertEquals(8, b.wtx("synthetic-id")!!["confirmations"]!!.jsonPrimitive.int)
        assertEquals(2,calls)
        val other = backend { Json.parseToJsonElement("""{"result":{"confirmations":0},"error":null}""").jsonObject }
        assertEquals(0, other.wtx("synthetic-id")!!["confirmations"]!!.jsonPrimitive.int)
    }

    @Test fun onlyRpcErrorMinusFiveMeansNonWalletAndIsNotPermanentlyCached() {
        var code = -5
        val b = backend { Json.parseToJsonElement("""{"result":null,"error":{"code":$code,"message":"mock"}}""").jsonObject }
        assertNull(b.wtx("synthetic-id"))
        code = -28
        assertEquals(-28, assertFailsWith<NodeRpcBackend.NodeException> { b.wtx("synthetic-id") }.rpcCode)
    }

    /**
     * The shared code calls every LND method, streams included, through LndEngine.call (as
     * Android's one gomobile function per method); each must be in the iOS engine's tables, or
     * paying fails with "no such LND call" (rc6, 16 nov 2026).
     */
    @Test fun everyLndMethodTheAppCallsIsInTheIosEngine() {
        val swift = File("../iosApp/iosApp/LndmobileEngine.swift").readText()
        val known = Regex("""^\s*"(\w+)": \{""", RegexOption.MULTILINE).findAll(swift).map { it.groupValues[1] }.toSet()
        val streams = Regex("""streams: \[[\s\S]*?\n    \]""").find(swift)!!.value
        assertTrue("if unary[method] == nil, let s = streams[method]" in swift, "call() must fall back to the streams")
        assertTrue("\"routerSendPaymentV2\"" in streams)
        val used = File("src/commonMain/kotlin/com/kilombino/kilowallet/core/ln").walk().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""engine\.(?:call|stream)\("(\w+)"""").findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(used.isNotEmpty())
        val missing = used - known
        assertTrue(missing.isEmpty(), "LND methods missing from LndmobileEngine.swift: $missing")
    }
}
