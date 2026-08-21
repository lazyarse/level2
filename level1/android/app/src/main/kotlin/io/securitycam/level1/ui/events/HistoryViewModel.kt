package io.securitycam.level1.ui.events

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.securitycam.level1.core.Snapshot
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.RecordedEventRow
import io.securitycam.level1.storage.RoomEventLog
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * History state: a selected day, its day-scoped event rows (timeline) and the
 * snapshot-carrying subset (gallery). Port of the design in
 * `docs/plans/2026-08-19-history-timeline-gallery-design.md`.
 */
class HistoryViewModel(
    private val dayLoader: suspend (LocalDate) -> List<RecordedEventRow>,
    private val snapshotLoader: suspend (String) -> Snapshot?,
    initialDay: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _day = MutableStateFlow(initialDay)
    val day: StateFlow<LocalDate> = _day.asStateFlow()

    /** Null while loading; empty when the selected day has no events. */
    private val _events = MutableStateFlow<List<RecordedEventRow>?>(null)
    val events: StateFlow<List<RecordedEventRow>?> = _events.asStateFlow()

    init {
        reload()
    }

    suspend fun loadSnapshot(name: String): Snapshot? = snapshotLoader(name)

    fun setDay(date: LocalDate) {
        _day.value = date
        reload()
    }

    fun previousDay() = setDay(_day.value.minusDays(1))

    fun nextDay() = setDay(_day.value.plusDays(1))

    fun reload() {
        viewModelScope.launch {
            _events.value = dayLoader(_day.value)
        }
    }

    companion object {
        const val DAY_LIMIT = 500

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as? Application
                    ?: error("Application missing from initializer")
                val context = app.applicationContext
                HistoryViewModel(
                    dayLoader = { date ->
                        val zone = ZoneId.systemDefault()
                        val start = date.atStartOfDay(zone).toInstant()
                        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
                        RoomEventLog(AppDatabase.get(context).eventDao())
                            .between(start, end, limit = DAY_LIMIT)
                    },
                    snapshotLoader = { name ->
                        FileSnapshotStore(File(context.filesDir, "snapshots").absolutePath)
                            .load(name)
                    },
                )
            }
        }
    }
}
