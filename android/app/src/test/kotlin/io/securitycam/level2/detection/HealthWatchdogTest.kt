package io.securitycam.level2.detection

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the local health watchdog (`docs/plans/2026-08-19-health-watchdog-design.md`). */
class HealthWatchdogTest {

    private val base = Instant.parse("2026-01-01T12:00:00Z")
    private val timeout = Duration.ofSeconds(30)

    private val episodes = mutableListOf<HealthEpisode>()

    private fun watchdog(): HealthWatchdog {
        episodes.clear()
        return HealthWatchdog({ episodes.add(it) }, stallTimeout = timeout)
    }

    private fun feed(d: HealthWatchdog, at: Instant) {
        d.noteFrame(at)
        d.noteAudio(at)
    }

    @Test
    fun noCheckUntilBothSourcesHaveSeenData() {
        val d = watchdog()
        // Only frames so far — a stalled audio stream can't be judged yet.
        for (s in 0 until 5) {
            d.noteFrame(base.plusSeconds(s.toLong()))
            d.check(base.plusSeconds(60 + s.toLong()))
        }
        assertTrue(episodes.isEmpty())

        d.noteAudio(base)
        d.check(base.plusSeconds(120))
        assertEquals(1, episodes.size)
        assertFalse(episodes[0].recovered)
    }

    @Test
    fun freshStreamsNeverAlert() {
        val d = watchdog()
        for (s in 0 until 10) {
            val now = base.plusSeconds(s * 5L)
            feed(d, now)
            d.check(now)
        }
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun stallFiresOnceAcrossTicksAndReportsSources() {
        val d = watchdog()
        feed(d, base)
        d.check(base.plusSeconds(31))
        assertEquals(1, episodes.size)
        val episode = episodes[0]
        assertFalse(episode.recovered)
        assertTrue(episode.frameStalled)
        assertTrue(episode.audioStalled)
        // Further ticks stay silent while the stall continues.
        for (extra in 1 until 4) d.check(base.plusSeconds(31L + extra))
        assertEquals(1, episodes.size)
    }

    @Test
    fun perSourceStalenessIsReported() {
        val d = watchdog()
        feed(d, base)
        // Frames keep flowing; audio goes quiet.
        for (s in 5..35 step 5) d.noteFrame(base.plusSeconds(s.toLong()))
        d.check(base.plusSeconds(40))
        assertEquals(1, episodes.size)
        val episode = episodes[0]
        assertFalse(episode.frameStalled)
        assertTrue(episode.audioStalled)
    }

    @Test
    fun recoveryFiresOnceWhenBothSourcesAreFreshAgain() {
        val d = watchdog()
        feed(d, base)
        d.check(base.plusSeconds(31))
        assertEquals(1, episodes.size)
        // Streams resume.
        feed(d, base.plusSeconds(32))
        d.check(base.plusSeconds(33))
        assertEquals(2, episodes.size)
        val recovery = episodes[1]
        assertTrue(recovery.recovered)
        // Recovery is not repeated.
        feed(d, base.plusSeconds(34))
        d.check(base.plusSeconds(35))
        assertEquals(2, episodes.size)
    }

    @Test
    fun resetRearmsTheWatchdog() {
        val d = watchdog()
        feed(d, base)
        d.check(base.plusSeconds(31))
        assertEquals(1, episodes.size)
        d.reset()
        // After reset nothing has data yet: checks are no-ops.
        d.check(base.plusSeconds(120))
        assertEquals(1, episodes.size)
        // And a new baseline re-arms normally.
        feed(d, base.plusSeconds(200))
        d.check(base.plusSeconds(210))
        assertEquals(1, episodes.size)
        d.check(base.plusSeconds(241))
        assertEquals(2, episodes.size)
        assertFalse(episodes[1].recovered)
    }

    @Test
    fun boundaryTimestampCountsAsFresh() {
        val d = watchdog()
        feed(d, base)
        d.check(base.plusSeconds(30))
        assertTrue(episodes.isEmpty())
    }
}
