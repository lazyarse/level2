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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Events list state (port of the Flutter `EventsScreen` loader pattern):
 * recent rows, snapshot lookup for thumbnails, and external-player launch.
 * [videoOpener] returning an error string surfaces it as a snackbar message;
 * a null opener hides the per-row play button (desktop parity).
 */
class EventsViewModel(
    private val loader: suspend () -> List<RecordedEventRow>,
    private val snapshotLoader: suspend (String) -> Snapshot?,
    private val videoOpener: ((String) -> String?)?,
) : ViewModel() {

    /** Null while loading; empty after a load with no rows. */
    private val _events = MutableStateFlow<List<RecordedEventRow>?>(null)
    val events: StateFlow<List<RecordedEventRow>?> = _events.asStateFlow()

    /** One-shot snackbar text; consume with [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        reload()
    }

    val hasVideoOpener: Boolean
        get() = videoOpener != null

    suspend fun loadSnapshot(name: String): Snapshot? = snapshotLoader(name)

    fun reload() {
        viewModelScope.launch {
            _events.value = loader()
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

    companion object {
        const val RECENT_LIMIT = 200

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as? Application
                    ?: error("Application missing from initializer")
                val context = app.applicationContext
                EventsViewModel(
                    loader = {
                        RoomEventLog(AppDatabase.get(context).eventDao()).recent(RECENT_LIMIT)
                    },
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
