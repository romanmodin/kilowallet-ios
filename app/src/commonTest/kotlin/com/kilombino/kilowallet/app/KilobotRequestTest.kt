package com.kilombino.kilowallet.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class KilobotRequestTest {
    private class Request : KilobotRequest {
        var cancellations = 0
        lateinit var complete: (String?) -> Unit
        fun start(callback: (String?) -> Unit): KilobotRequest {
            complete = callback
            return this
        }
        override fun cancel() { cancellations++ }
    }

    @Test fun dismissalCancelsOnlyItsFetchAndIgnoresALateReply() = runTest {
        val old = Request()
        val fresh = Request()
        val first = async(start = CoroutineStart.UNDISPATCHED) { awaitKilobotOffers(old::start) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { awaitKilobotOffers(fresh::start) }
        first.cancelAndJoin()
        old.complete("stale")
        fresh.complete("current")
        assertEquals("current", second.await())
        assertEquals(1, old.cancellations)
        assertEquals(0, fresh.cancellations)
    }

    @Test fun dismissalBeforeStartReturnsStillCancelsTheNativeHandle() = runTest {
        val req = Request()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitKilobotOffers { callback ->
                cancel()
                req.start(callback)
            }
        }
        pending.join()
        assertEquals(1, req.cancellations)
    }

    @Test fun synchronousUnavailableReplyNeedsNoHandle() = runTest {
        val unavailable = awaitKilobotOffers { complete ->
            complete(null)
            null
        }
        assertNull(unavailable)
    }
}
