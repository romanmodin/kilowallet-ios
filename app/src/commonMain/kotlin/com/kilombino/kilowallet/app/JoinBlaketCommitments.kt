package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.joinblaket.JsonCommitmentStore

/** Capture the owning account at construction; switching the UI cannot redirect writes. */
internal fun walletJoinBlaketCommitments(prefs: Prefs, zpub: String): JsonCommitmentStore {
    val key = "joinblaket.commitments.v1." + WalletCatalog.idFor(zpub)
    return JsonCommitmentStore(
        load = { prefs.get(key) },
        save = { text -> prefs.put(key, text); prefs.flush() },
    )
}
