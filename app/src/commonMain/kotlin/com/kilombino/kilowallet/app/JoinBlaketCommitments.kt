package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.joinblaket.JsonCommitmentStore

/** Capture the owning account at construction; switching the UI cannot redirect writes. */
internal fun walletJoinBlaketCommitments(prefs: Prefs, zpub: String): JsonCommitmentStore {
    val key = "joinblaket.commitments.v1." + WalletCatalog.idFor(zpub)
    val store = JsonCommitmentStore(
        load = { prefs.get(key) },
        save = { text -> prefs.put(key, text); prefs.flush() },
    )
    // Older upstream installs used a global history. Conservatively burn those commitments
    // for this account too, keeping the original bytes until every wallet has migrated.
    prefs.get("joinblaket.commitments")?.let { store.mergeFrom(it) }
    return store
}

/** Malformed backup history or a failed flush aborts restore before seed/catalog mutation. */
internal fun mergeJoinBlaketBackup(prefs: Prefs, zpub: String, value: kotlinx.serialization.json.JsonElement?) {
    if (value == null) return
    require(value is kotlinx.serialization.json.JsonObject) { "Invalid JoinBlaket backup history." }
    walletJoinBlaketCommitments(prefs, zpub).mergeFrom(value.toString())
}
