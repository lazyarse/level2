package io.securitycam.level2.core

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Recurring weekly exclusion window (port of the design in
 * `docs/plans/2026-08-19-monitoring-schedule-design.md`). Monitoring pauses
 * while "now" falls inside any enabled window.
 *
 * [days] is a weekday bitmask: bit0 = Monday … bit6 = Sunday (0 = never).
 * [startHour]:[startMinute] is inclusive, [endHour]:[endMinute] exclusive;
 * end == start means a 24 h window; ranges wrap at midnight (22:00→06:00).
 */
data class ScheduleWindow(
    val id: String,
    val days: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
) {
    /** Weekday bit for [DayOfWeek] (Mon=0 … Sun=6). */
    fun matchesDay(now: LocalDateTime): Boolean =
        days and (1 shl (now.dayOfWeek.value - 1)) != 0

    /** Whether [now]'s time-of-day falls inside this window (assumes day matched). */
    fun matchesTime(now: LocalDateTime): Boolean {
        val cur = now.hour * 60 + now.minute
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        if (start == end) return true // 24 h window
        return if (start < end) cur >= start && cur < end else cur >= start || cur < end
    }

    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id,
        "days" to days,
        "startHour" to startHour,
        "startMinute" to startMinute,
        "endHour" to endHour,
        "endMinute" to endMinute,
        "enabled" to enabled,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): ScheduleWindow = ScheduleWindow(
            id = json["id"] as String,
            days = (json["days"] as? Number)?.toInt() ?: 0,
            startHour = (json["startHour"] as? Number)?.toInt() ?: 0,
            startMinute = (json["startMinute"] as? Number)?.toInt() ?: 0,
            endHour = (json["endHour"] as? Number)?.toInt() ?: 0,
            endMinute = (json["endMinute"] as? Number)?.toInt() ?: 0,
            enabled = json["enabled"] as? Boolean ?: true,
        )
    }
}

/** Pure policy over [ScheduleWindow]s (no timers, fully unit-testable). */
object SchedulePolicy {
    /** True iff [now] falls inside any enabled window matching its weekday. */
    fun isExcluded(windows: List<ScheduleWindow>, now: LocalDateTime): Boolean =
        windows.any { w ->
            w.enabled && w.matchesDay(now) && w.matchesTime(now)
        }
}
