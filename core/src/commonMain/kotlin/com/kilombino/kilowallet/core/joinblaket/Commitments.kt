package com.kilombino.kilowallet.core.joinblaket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


/**
 * The PoDLE commitments this wallet already showed to makers (`taker/podle_manager.py`). A
 * commitment is burned once a maker has seen it: makers broadcast `!hp2 <commitment>` and every
 * maker then refuses it, so one is never sent twice. It is marked used BEFORE it is sent, so a
 * crash in between wastes an index instead of reusing one.
 */
interface CommitmentStore {
    fun isUsed(commitmentHex: String): Boolean
    /** Record [commitmentHex] as used, durably, before returning. */
    fun markUsed(commitmentHex: String)
}

/**
 * [CommitmentStore] in Python's `cmtdata/commitments.json` format, `{"used": [hex…], "external": {}}`,
 * the same text Kilowallet for Android keeps (and puts in the .kab as `joinblaket_commitments`).
 * Where the text lives is the platform's: [load] gives it (null when nothing was used yet) and
 * [save] must keep it durably before returning (the app: its settings, written to disk at once).
 */
class JsonCommitmentStore(private val load: () -> String?, private val save: (String) -> Unit) : CommitmentStore {
    private fun parse(text: String): MutableSet<String> {
        val a = JbJson.obj(text)["used"] as? JsonArray
            ?: error("Invalid saved JoinBlaket commitment history.")
        return a.mapTo(LinkedHashSet()) {
            val value = it as? JsonPrimitive
            require(value != null && value.isString) { "Invalid saved JoinBlaket commitment." }
            normalize(value.content)
        }
    }

    private fun read(): MutableSet<String> = load()?.let(::parse) ?: LinkedHashSet()

    private fun normalize(value: String): String {
        require(value.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid JoinBlaket commitment." }
        return value.lowercase()
    }

    override fun isUsed(commitmentHex: String): Boolean = normalize(commitmentHex) in read()

    override fun markUsed(commitmentHex: String) {
        val used = read()
        used.add(normalize(commitmentHex))
        // Retry persistence even if an earlier flush failed after updating an in-memory cache.
        save(format(used))
    }

    /** The JSON for a backup, or null when nothing was used yet. */
    fun export(): String? = load()?.also { parse(it) }

    /**
     * Adds the used commitments of a backup ([json], the same format) to these: a restore never
     * forgets one already used here, and never brings back an index the backup had burned.
     */
    fun mergeFrom(json: String) {
        // Validate the entire backup before any mutation; corrupt records must never reset history.
        val imported = parse(json)
        val used = read()
        if (used.addAll(imported)) save(format(used))
    }

    /** How many commitments were used: for tests and the backup summary. */
    fun count(): Int = read().size

    companion object {
        /** `{"used":[…],"external":{}}`, as Android's org.json writes it. */
        fun format(used: Collection<String>): String =
            JsonObject(mapOf("used" to JsonArray(used.map { JsonPrimitive(it) }), "external" to JsonObject(emptyMap()))).toString()
    }
}
