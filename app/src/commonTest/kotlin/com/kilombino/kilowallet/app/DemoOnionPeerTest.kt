package com.kilombino.kilowallet.app

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DemoOnionPeerTest {
    @Test fun waitsForMatchingNodeAndTransport() = runTest {
        var reads = 0
        assertTrue(awaitDemoOnionPeer("k", "a.onion:9735") {
            when (++reads) {
                1 -> error("RPC starting")
                2 -> emptyMap()
                3 -> mapOf("other" to "a.onion:9735")
                4 -> mapOf("k" to "127.0.0.1:9735")
                else -> mapOf("k" to "a.onion:9735")
            }
        })
        assertEquals(5, reads)
    }
    @Test fun wrongTransportNeverQualifies() = runTest {
        var reads = 0
        assertFalse(awaitDemoOnionPeer("k", "a.onion:9735") {
            reads++; mapOf("k" to "127.0.0.1:9735")
        })
        assertEquals(10, reads)
    }
}
