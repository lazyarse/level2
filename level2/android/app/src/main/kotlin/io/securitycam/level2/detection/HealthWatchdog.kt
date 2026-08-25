package io.securitycam.level2.detection

import java.time.Duration
import java.time.Instant

/** A stall or recovery episode reported by [HealthWatchdog]. */
data class HealthEpisode(
    /** True when the streams recovered, false when a stall was first detected. */
    val recovered: Boolean,
    val frameStalled: Boolean,
    val audioStalled: Boolean,
    /** How long since the freshest data at detection time. */
    val elapsed: Duration,
)

/**
 * Local stream-liveness watchdog (port of the design in
 * `docs/plans/2026-08-19-health-watchdog-design.md`). The controller calls
 * [noteFrame]/[noteAudio] per analysis frame / audio window and [check] on a
 * periodic tick. One stall alert per episode by construction (flags), plus a
 * recovery event when both sources are fresh again.
 *
 * Arming: timestamps start null and [check] is a no-op until **both** sources
 * have seen data, so manual starts that never produce frames don't trip it.
 */
class HealthWatchdog(
    private val onEpisode: (HealthEpisode) -> Unit,
    private val stallTimeout: Duration = DEFAULT_STALL_TIMEOUT,
) {
    private var lastFrame: Instant? = null
    private var lastAudio: Instant? = null
    private var frameStalled = false
    private var audioStalled = false
    private var episodeActive = false

    fun noteFrame(at: Instant) {
        lastFrame = at
    }

    fun noteAudio(at: Instant) {
        lastAudio = at
    }

    fun check(now: Instant) {
        val frameAt = lastFrame ?: return
        val audioAt = lastAudio ?: return
        if (!episodeActive) {
            val fStale = !isFresh(frameAt, now)
            val aStale = !isFresh(audioAt, now)
            if (!fStale && !aStale) return
            episodeActive = true
            frameStalled = fStale
            audioStalled = aStale
            onEpisode(
                HealthEpisode(
                    recovered = false,
                    frameStalled = fStale,
                    audioStalled = aStale,
                    elapsed = Duration.between(maxOf(frameAt, audioAt), now),
                ),
            )
        } else if (isFresh(frameAt, now) && isFresh(audioAt, now)) {
            episodeActive = false
            frameStalled = false
            audioStalled = false
            onEpisode(
                HealthEpisode(
                    recovered = true,
                    frameStalled = false,
                    audioStalled = false,
                    elapsed = Duration.ZERO,
                ),
            )
        }
    }

    fun reset() {
        lastFrame = null
        lastAudio = null
        frameStalled = false
        audioStalled = false
        episodeActive = false
    }

    private fun isFresh(at: Instant, now: Instant): Boolean =
        Duration.between(at, now) <= stallTimeout

    companion object {
        val DEFAULT_STALL_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val DETAIL_STALL = "stall"
        const val DETAIL_RECOVERED = "recovered"
    }
}
