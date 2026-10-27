package ru.outsideproarts.oassist.core.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Tracks work accepted synchronously even if its actual dispatch happens later. */
internal class SpeechCompletionTracker {
    private val lock = Any()
    private val pending = mutableSetOf<CompletableDeferred<Unit>>()

    fun register(): CompletableDeferred<Unit> = synchronized(lock) {
        CompletableDeferred<Unit>().also { pending.add(it) }
    }

    fun complete(receipt: CompletableDeferred<Unit>) {
        synchronized(lock) { pending.remove(receipt) }
        receipt.complete(Unit)
    }

    fun completeAll() {
        val receipts = synchronized(lock) {
            pending.toList().also { pending.clear() }
        }
        receipts.forEach { it.complete(Unit) }
    }

    fun snapshot(): List<Deferred<Unit>> = synchronized(lock) { pending.toList() }
}
