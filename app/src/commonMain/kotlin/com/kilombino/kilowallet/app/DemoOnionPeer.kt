package com.kilombino.kilowallet.app

import kotlinx.coroutines.delay

/** ConnectPeer may return before ListPeers publishes the transport. Observe; do not redial. */
internal suspend fun awaitDemoOnionPeer(
    node: String, onion: String, readPeers: suspend () -> Map<String, String>
): Boolean {
    repeat(10) { attempt ->
        if (attempt > 0) delay(1_000)
        val peers = try {
            readPeers()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        if (peers[node] == onion) return true
    }
    return false
}
