package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.Protocol

/** Display-only fixture. Public blind key from Android KilojoinVectors, commit f5bef5143cdf0ab45e2e7483c3a3171c00f1990c.
 * No RSA private key is embedded or generated; production creation still uses PoolSession.newPool. */
internal object ScreenshotPoolFixture {
    const val BLIND_PUBLIC = "b6d01ca7217418aea74bec3d7a18950d9608aec1b574aabc4ee9b870c0d137ebd78c83ac4de8fd8d22c6e72215c6f15be8df4c2fa7fd7e19373fff17ed52afaa4b27027564eda9840dd3134e48c5d32eeb8011f21949eed0ce57c9922d3edac20f14867ee99adbff50bb71328753765a032be6e01b8322f62c78b5c1495cdcb52bed44751047301004e51c59968a8d84920147e83bf34f90c8d9be5766afa9c77d50ef40dbac4a35d1a1cfb63027880193339c09bd3498b6ad8c7de6f317d10126adaadd9f4c5965f0b8a4ee44a29697faea1fd94a26d6808f6bae3011ba67295bb281dd93f2832ef23dec22ee7fef39a91f426133188117210b6072e6620df5:10001"
    fun create(now: Long) = run {
        val base = PoolSession.newPool(50_000, 2.0, 5, 6, v3 = false, now = now)
        base.copy(terms = base.terms.copy(version = Protocol.V3,
            creatorPub = NostrEvent.pubOf(NostrEvent.newSecret()), blindKey = BLIND_PUBLIC))
    }
}
