package com.kilombino.kilowallet.app

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** A single public offer fetch. Cancelling it must leave the shared Tor service running. */
interface KilobotRequest {
    fun cancel()
}

internal suspend fun awaitKilobotOffers(
    start: ((String?) -> Unit) -> KilobotRequest?,
): String? = suspendCancellableCoroutine { continuation ->
    if (!continuation.isActive) return@suspendCancellableCoroutine
    val request = start { summary -> if (continuation.isActive) continuation.resume(summary) }
    // Cancellation during start also invokes this handler as soon as the handle is available.
    continuation.invokeOnCancellation { request?.cancel() }
}
