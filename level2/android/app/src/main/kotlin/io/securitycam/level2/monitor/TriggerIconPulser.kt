package io.securitycam.level2.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Turns trigger-event edges into a time-windowed "active types" set for UI
 * icons.
 *
 * Each event pulses its type active and (re)schedules removal after
 * [durationMs]; a repeat event inside the window resets that type's timer, so
 * continuous detection keeps the icon solid while silence clears it. Events
 * are edges — identical consecutive triggers must each re-pulse, which an
 * accumulating StateFlow<Set> cannot express (equal values conflate), hence
 * this dedicated path.
 */
class TriggerIconPulser(
    private val scope: CoroutineScope,
    private val durationMs: Long,
    private val onActive: (Set<String>) -> Unit,
) {
    private val active = LinkedHashSet<String>()
    private val removals = mutableMapOf<String, Job>()


    fun onEvent(type: String) {
        removals.remove(type)?.cancel()
        active.add(type)
        onActive(active.toSet())
        removals[type] = scope.launch {
            
            delay(durationMs)
            removals.remove(type)
            active.remove(type)
            onActive(active.toSet())
        }
    }

    /** Clears everything immediately (monitoring stopped). */
    fun reset() {
        removals.values.forEach { it.cancel() }
        removals.clear()
        val had = active.isNotEmpty()
        active.clear()
        if (had) onActive(emptySet())
    }
}
