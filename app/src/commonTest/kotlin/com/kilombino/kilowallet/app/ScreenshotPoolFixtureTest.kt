package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.kilojoin.BlindRsa
import com.kilombino.kilowallet.core.kilojoin.Protocol
import kotlin.test.*

class ScreenshotPoolFixtureTest {
    @Test fun validPublicFixtureWithoutCreatorSigningSecrets() {
        val p = ScreenshotPoolFixture.create(1800000000)
        assertEquals(ScreenshotPoolFixture.BLIND_PUBLIC, BlindRsa.PublicKey.decode(p.terms.blindKey!!).encode())
        assertNull(p.blindPriv)
        assertNull(p.creatorSecret)
        for (t in listOf(p.terms, p.terms.copy(version = Protocol.V4, outputs = 2),
            p.terms.copy(version = Protocol.V5, outputs = 2, amount = 25000, payments = listOf(20000, 30000)))) {
            assertEquals(t, Protocol.Terms.fromJson(t.toJson(), t.createdAt))
        }
    }
}
