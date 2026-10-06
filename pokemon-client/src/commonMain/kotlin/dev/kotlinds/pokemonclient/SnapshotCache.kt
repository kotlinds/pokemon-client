package dev.kotlinds.pokemonclient

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A cache read from several threads (the console's and the MCP server's) without locks, in common code: the entries are
 * an immutable map published atomically (compute, then publish a copy with the new entry), like the event log
 * ([dev.kotlinds.pokemonclient.state.EventLog]). Two threads asking for the same missing key may both compute it; the
 * first published value wins and both return it. Meant for decoded data that is small and grows slowly (maps, areas).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SnapshotCache<K, V : Any> {
    private val entries = AtomicReference<Map<K, V>>(emptyMap())

    /** The value of [key], computed by [compute] (and kept) the first time. */
    fun getOrPut(key: K, compute: () -> V): V {
        entries.load()[key]?.let { return it }
        val value = compute()
        while (true) {
            val current = entries.load()
            current[key]?.let { return it }
            if (entries.compareAndSet(current, current + (key to value))) return value
        }
    }

    /** The value of [key], computed by [compute] the first time; a null result isn't kept (asked again next time). */
    fun getOrPutNotNull(key: K, compute: () -> V?): V? =
        entries.load()[key] ?: compute()?.let { value -> getOrPut(key) { value } }
}
