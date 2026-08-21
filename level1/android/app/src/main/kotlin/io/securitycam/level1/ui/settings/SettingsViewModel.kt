package io.securitycam.level1.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.securitycam.level1.camera_service.VideoClipRecorder
import io.securitycam.level1.core.AppSettings
import io.securitycam.level1.storage.AppDatabase
import io.securitycam.level1.storage.EncryptedSecretStore
import io.securitycam.level1.storage.FileSnapshotStore
import io.securitycam.level1.storage.RoomEventLog
import io.securitycam.level1.storage.SettingsStore
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Draft-commit settings state (port of the Flutter `SettingsScreen._draft` +
 * `MonitorController.updateSettings` pattern). Loads once, mutates a draft,
 * and persists on save. Event clearing mirrors the Dart controller's
 * `_deleteOlderThan`: rows first, then snapshot files and gallery clips.
 */
class SettingsViewModel(
    private val settingsLoader: suspend () -> AppSettings,
    private val settingsSaver: suspend (AppSettings) -> Unit,
    private val eventsClearer: suspend (Duration?) -> Unit,
) : ViewModel() {

    /** Null until the stored settings finish loading. */
    private val _draft = MutableStateFlow<AppSettings?>(null)
    val draft: StateFlow<AppSettings?> = _draft.asStateFlow()

    /** One-shot snackbar text; consume with [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _draft.value = settingsLoader()
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save() {
        val current = _draft.value ?: return
        viewModelScope.launch {
            settingsSaver(current)
            _message.value = "Settings saved"
        }
    }

    fun clearEvents(olderThan: Duration?) {
        viewModelScope.launch {
            eventsClearer(olderThan)
            _message.value = "Events cleared"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        /**
         * Default event purge used by the Settings screen buttons (and the same
         * shape as MonitorViewModel's retention sweep).
         */
        fun defaultEventsClearer(application: Application): suspend (Duration?) -> Unit =
            { olderThan ->
                val context = application.applicationContext
                val cutoff = olderThan?.let { Instant.now().minus(it) }
                val deleted = RoomEventLog(AppDatabase.get(context).eventDao()).deleteEvents(cutoff)
                val snapshots = FileSnapshotStore(File(context.filesDir, "snapshots").absolutePath)
                for (name in deleted.snapshotNames) {
                    runCatching { snapshots.delete(name) }
                }
                for (name in deleted.videoNames) {
                    runCatching { VideoClipRecorder.delete(name) }
                }
            }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as? Application
                    ?: error("Application missing from initializer")
                SettingsViewModel(
                    settingsLoader = {
                        SettingsStore(app, EncryptedSecretStore(app)).load()
                    },
                    settingsSaver = { settings ->
                        SettingsStore(app, EncryptedSecretStore(app)).save(settings)
                    },
                    eventsClearer = defaultEventsClearer(app),
                )
            }
        }
    }
}
