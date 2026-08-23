package io.securitycam.level1.ui.events

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.securitycam.level1.camera_service.VideoClipRecorder
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.RecordedEventRow
import io.securitycam.level1.storage.RoomEventLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Presentation mode of the merged events screen. */
enum class EventsViewMode { LIST, GRID }

/** One calendar day's events, newest first. */
data class DaySection(
    val date: LocalDate,
    val rows: List<RecordedEventRow>,
)

/**
 * Merged events screen state: paged, day-grouped trigger events with a list
 * and a gallery view. Pages load backwards one day at a time via [pageLoader]
 * (a `between` window query); empty days are skipped in bounded sweeps until
 * the log floor ([floorLoader]) is reached, which ends scrolling.
 * [videoOpener] returning an error string surfaces it as a snackbar message;
 * a null opener hides the per-row play button.
 */
class EventsViewModel(
    private val pageLoader: suspend (startInclusive: Instant, endExclusive: Instant) -> List<RecordedEventRow>,
    private val floorLoader: suspend () -> Instant?,
    private val snapshotLoader: suspend (String) -> Snapshot?,
    private val videoOpener: ((String) -> String?)?,
    /**
     * Row-count changes from the event store; every increase triggers a live
     * reload so freshly recorded events appear without an app restart.
     */
    private val countLoader: (() -> kotlinx.coroutines.flow.Flow<Long>)? = null,
    private val todayProvider: () -> LocalDate = LocalDate::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    /** Null while loading; non-empty sections are sorted newest day first. */
    private val _sections = MutableStateFlow<List<DaySection>?>(null)
    val sections: StateFlow<List<DaySection>?> = _sections.asStateFlow()

    private val _viewMode = MutableStateFlow(EventsViewMode.LIST)
    val viewMode: StateFlow<EventsViewMode> = _viewMode.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** One-shot snackbar text; consume with [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Exclusive upper bound (oldest loaded day) of the next backward query. */
    private var nextEnd: LocalDate = todayProvider().plusDays(1)
    private var floorInstant: Instant? = null
    private var reachedFloor = false

    /** Rows already shown; guards against overlapping windows re-listing them. */
    private val seenIds = HashSet<Long>()

    private var refreshInFlight = false

    init {
        reload()
        countLoader?.let { loader ->
            viewModelScope.launch {
                loader()
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        if (!refreshInFlight) {
                            refreshInFlight = true
                            try {
                                reload()
                            } finally {
                                refreshInFlight = false
                            }
                        }
                    }
            }
        }
    }

    val hasVideoOpener: Boolean
        get() = videoOpener != null

    suspend fun loadSnapshot(name: String): Snapshot? = snapshotLoader(name)

    fun setViewMode(mode: EventsViewMode) {
        _viewMode.value = mode
    }

    fun reload() {
        viewModelScope.launch {
            _sections.value = null
            seenIds.clear()
            floorInstant = floorLoader()
            reachedFloor = false
            nextEnd = todayProvider().plusDays(1)
            val found = mutableListOf<DaySection>()
            for (i in 0 until INITIAL_SCAN_DAYS) {
                if (!moreDaysPossible()) break
                val section = loadDay(nextEnd.minusDays(1))
                if (section != null) {
                    found += section
                    if (found.sumOf { it.rows.size } >= MIN_INITIAL_ROWS) break
                }
            }
            settleFloor()
            _sections.value = found.sortedByDescending { it.date }
        }
    }

    /** Appends older pages when the user scrolls near the end of the list. */
    fun loadOlder() {
        if (!_hasMore.value || _loadingOlder.value || _sections.value == null) return
        _loadingOlder.value = true
        viewModelScope.launch {
            _loadingOlder.value = true
            try {
                var added = false
                var scanned = 0
                while (!reachedFloor && !added && scanned < MAX_SWEEP_DAYS) {
                    val section = loadDay(nextEnd.minusDays(1))
                    if (section != null) {
                        _sections.value = _sections.value.orEmpty() + section
                        added = true
                    } else {
                        scanned++
                    }
                }
                settleFloor()
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    fun playVideo(name: String) {
        val opener = videoOpener ?: return
        viewModelScope.launch {
            val error = opener(name)
            if (error != null) {
                _message.value = "Could not play video: $error"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Queries the single day ending at [nextEnd] and advances the cursor. */
    private suspend fun loadDay(day: LocalDate): DaySection? {
        val startInstant = day.atStartOfDay(zone).toInstant()
        val endInstant = nextEnd.atStartOfDay(zone).toInstant()
        nextEnd = day
        val rows = pageLoader(startInstant, endInstant).filter { seenIds.add(it.id) }
        return if (rows.isEmpty()) null else DaySection(day, rows)
    }

    /** True while unscanned days could still contain rows above the floor. */
    private fun moreDaysPossible(): Boolean {
        val floor = floorInstant ?: return false
        return nextEnd.atStartOfDay(zone).toInstant() > floor
    }

    /** Re-evaluates [reachedFloor]/[hasMore] after the cursor moved back. */
    private fun settleFloor() {
        if (moreDaysPossible()) {
            // A partially-covered boundary day still counts as exhausted only
            // once its start instant has passed the floor; otherwise more
            // backward days remain reachable.
            _hasMore.value = true
        } else {
            reachedFloor = true
            _hasMore.value = false
        }
    }

    companion object {
        const val PAGE_LIMIT = 500
        const val INITIAL_SCAN_DAYS = 7
        const val MIN_INITIAL_ROWS = 20
        const val MAX_SWEEP_DAYS = 14

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as? Application
                    ?: error("Application missing from initializer")
                val context = app.applicationContext
                val log = RoomEventLog(AppDatabase.get(context).eventDao())
                EventsViewModel(
                    pageLoader = { start, end -> log.between(start, end, limit = PAGE_LIMIT) },
                    floorLoader = { log.oldestInstant() },
                    countLoader = { log.countFlow() },
                    snapshotLoader = { name ->
                        FileSnapshotStore(File(context.filesDir, "snapshots").absolutePath)
                            .load(name)
                    },
                    videoOpener = { name -> VideoClipRecorder.open(name) },
                )
            }
        }
    }
}
