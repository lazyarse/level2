package io.securitycam.level2.core

import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the planned `test/schedule_policy_test.dart`. */
class SchedulePolicyTest {

    private fun window(
        days: Int = 0b1111111,
        startHour: Int = 22,
        startMinute: Int = 0,
        endHour: Int = 6,
        endMinute: Int = 0,
        enabled: Boolean = true,
    ) = ScheduleWindow(
        id = "w1",
        days = days,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        enabled = enabled,
    )

    private val monday2300 = LocalDateTime.of(2026, 1, 5, 23, 0) // Monday
    private val monday1200 = LocalDateTime.of(2026, 1, 5, 12, 0)
    private val monday0530 = LocalDateTime.of(2026, 1, 5, 5, 30)

    @Test
    fun emptyListIsNeverExcluded() {
        assertFalse(SchedulePolicy.isExcluded(emptyList(), monday2300))
    }

    @Test
    fun overnightWindowExcludesLateNightAndEarlyMorning() {
        val windows = listOf(window()) // 22:00 → 06:00 every day
        assertTrue(SchedulePolicy.isExcluded(windows, monday2300))
        assertTrue(SchedulePolicy.isExcluded(windows, monday0530)) // wraps into Monday
        assertFalse(SchedulePolicy.isExcluded(windows, monday1200))
        // Boundaries: inclusive start, exclusive end.
        assertTrue(SchedulePolicy.isExcluded(windows, LocalDateTime.of(2026, 1, 5, 22, 0)))
        assertFalse(SchedulePolicy.isExcluded(windows, LocalDateTime.of(2026, 1, 5, 6, 0)))
    }

    @Test
    fun weekdayBitmaskMatchesOnlySelectedDays() {
        // Monday only (bit0).
        val windows = listOf(window(days = 0b0000001))
        assertTrue(SchedulePolicy.isExcluded(windows, monday2300))
        assertFalse(
            SchedulePolicy.isExcluded(
                windows,
                LocalDateTime.of(2026, 1, 6, 23, 0), // Tuesday
            ),
        )
    }

    @Test
    fun twentyFourHourWindowExcludesAllDay() {
        val windows = listOf(window(startHour = 9, startMinute = 30, endHour = 9, endMinute = 30))
        assertTrue(SchedulePolicy.isExcluded(windows, monday1200))
        assertTrue(SchedulePolicy.isExcluded(windows, monday0530))
    }

    @Test
    fun disabledWindowsAreIgnored() {
        val windows = listOf(window(enabled = false))
        assertFalse(SchedulePolicy.isExcluded(windows, monday2300))
    }

    @Test
    fun sameDayNonWrappingWindow() {
        val windows = listOf(window(startHour = 12, endHour = 13))
        assertFalse(SchedulePolicy.isExcluded(windows, LocalDateTime.of(2026, 1, 5, 11, 59)))
        assertTrue(SchedulePolicy.isExcluded(windows, monday1200))
        assertFalse(SchedulePolicy.isExcluded(windows, LocalDateTime.of(2026, 1, 5, 13, 0)))
    }

    @Test
    fun jsonRoundTripPreservesFields() {
        val original = window(days = 0b0101010, enabled = false)
        val restored = ScheduleWindow.fromJson(original.toJson())
        org.junit.Assert.assertEquals(original, restored)
    }
}
