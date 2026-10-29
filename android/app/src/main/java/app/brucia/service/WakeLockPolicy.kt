package app.brucia.service

/**
 * Pure, Android-free bookkeeping for when a PARTIAL_WAKE_LOCK should be held: a set of named
 * "reasons" (connection open, a capture running, a stream active...). The lock is held while any
 * reason is present. [add] / [remove] return true exactly on the transition into / out of the held
 * state, so the caller acquires on the first reason and releases on the last - guaranteeing no leak
 * and no double-acquire regardless of call order. JVM-testable with no PowerManager.
 */
class WakeLockPolicy {

    private val reasons = LinkedHashSet<String>()

    /** True while at least one reason keeps the lock held. */
    val held: Boolean get() = reasons.isNotEmpty()

    /** Reasons currently holding the lock (for diagnostics / the notification text). */
    fun reasons(): Set<String> = reasons.toSet()

    /** Add a reason. Returns true if this transitioned the lock from released to held. */
    fun add(reason: String): Boolean {
        val wasEmpty = reasons.isEmpty()
        reasons.add(reason)
        return wasEmpty && reasons.isNotEmpty()
    }

    /** Remove a reason. Returns true if this transitioned the lock from held to released. */
    fun remove(reason: String): Boolean {
        if (!reasons.remove(reason)) return false
        return reasons.isEmpty()
    }

    /** Clear all reasons. Returns true if the lock was held and is now released. */
    fun clear(): Boolean {
        val wasHeld = reasons.isNotEmpty()
        reasons.clear()
        return wasHeld
    }
}
