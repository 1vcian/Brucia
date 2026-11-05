package app.brucia.ui

/**
 * Leave actions of screens that were disposed only because the Activity was being recreated
 * (rotation, window resize, fold/unfold). Work such as a device stream keeps running across the
 * recreation; what happens next decides whether it stops:
 * - the screen composes again in the new Activity and claims its action back ([enter]);
 * - the screen does not come back (its tool was left while the Activity was being recreated, or
 *   the restored UI shows something else), so [settle], run once the new UI has composed, runs the
 *   action as if the screen had been left normally.
 * A dispose that is not part of a configuration change runs its action at once ([leave]).
 *
 * Main-thread only, like composition.
 */
class ParkedLeaves {
    private val parked = LinkedHashMap<String, () -> Unit>()

    /** Number of actions waiting for [enter] or [settle]. */
    val size: Int get() = parked.size

    /** The screen [id] is composed (again): its parked action, if any, is no longer pending. */
    fun enter(id: String) {
        parked.remove(id)
    }

    /** The screen [id] left composition. */
    fun leave(id: String, changingConfigurations: Boolean, onLeave: () -> Unit) {
        if (changingConfigurations) parked[id] = onLeave else onLeave()
    }

    /** The recreated UI has composed: run the actions of screens that did not come back. */
    fun settle() {
        if (parked.isEmpty()) return
        val stale = parked.values.toList()
        parked.clear()
        stale.forEach { it() }
    }
}
