package io.securitycam.level2.core

import io.securitycam.level2.detection.DetectionZone
import io.securitycam.level2.detection.DetectorConfig
import java.time.Duration

/** Video clip recording resolution tiers (Android only). */
object VideoQuality {
    const val lowest = "lowest"
    const val sd = "sd"
    const val hd = "hd"
    const val fhd = "fhd"
    const val uhd = "uhd"
    const val highest = "highest"

    val values = listOf(lowest, sd, hd, fhd, uhd, highest)

    fun label(value: String): String = when (value) {
        sd -> "SD (480p)"
        hd -> "HD (720p)"
        fhd -> "Full HD (1080p)"
        uhd -> "UHD (4K)"
        highest -> "Highest (device maximum)"
        lowest -> "Lowest (device minimum)"
        else -> "Lowest (device minimum)"
    }
}

/** Analysis stream resolution presets (single stream for motion + face/person). */
object AnalysisResolution {
    const val low = "low"
    const val balanced = "balanced"
    const val high = "high"

    val values = listOf(low, balanced, high)

    fun size(value: String): Pair<Int, Int> = when (value) {
        low -> 160 to 120
        high -> 640 to 480
        else -> 320 to 240
    }

    fun label(value: String): String = when (value) {
        low -> "Low (160x120)"
        high -> "High (640x480)"
        else -> "Balanced (320x240)"
    }
}

/**
 * Detection speed tiers: how often the heavy gated ML pass may run during
 * continuous motion (see `DetectorPipeline.gatedMinInterval`). Lower tiers
 * trade detection latency for CPU/battery on older hardware. The YOLO model
 * input itself is fixed at 640×640 (LiteRT exposes no resize), so pacing —
 * not smaller inputs — is the lever.
 */
object DetectionSpeed {
    const val accuracy = "accuracy"
    const val balanced = "balanced"
    const val fastest = "fastest"

    val values = listOf(accuracy, balanced, fastest)

    /** Minimum gap between heavy gated passes for a tier. */
    fun gatedInterval(value: String): Duration = when (value) {
        balanced -> Duration.ofMillis(1500)
        fastest -> Duration.ofMillis(3000)
        else -> Duration.ofMillis(750)
    }

    fun label(value: String): String = when (value) {
        balanced -> "Balanced"
        fastest -> "Fastest (older phones)"
        else -> "Best accuracy"
    }
}

/** Live View streaming settings. */
data class LiveViewSettings(
    val enabled: Boolean = false,
    val mode: String = "server",       // "server" | "push"
    val port: Int = 8554,
    val username: String = "",
    val password: String = "",         // routed through SecretStore
    val relayUrl: String = "",         // rtsp://user:pass@host/path
    val resolution: String = "720p",   // "480p" | "720p" | "1080p"
    val fps: Int = 15,
    val audioEnabled: Boolean = true,
    val talkBackEnabled: Boolean = false,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "mode" to mode,
        "port" to port,
        "username" to username,
        "relayUrl" to relayUrl,
        "resolution" to resolution,
        "fps" to fps,
        "audioEnabled" to audioEnabled,
        "talkBackEnabled" to talkBackEnabled,
    )

    companion object {
        const val SECRET_FIELD = "password"

        fun fromJson(json: Map<String, Any?>): LiveViewSettings = LiveViewSettings(
            enabled = json["enabled"] as? Boolean ?: false,
            mode = json["mode"] as? String ?: "server",
            port = (json["port"] as? Number)?.toInt() ?: 8554,
            username = json["username"] as? String ?: "",
            password = json["password"] as? String ?: "",
            relayUrl = json["relayUrl"] as? String ?: "",
            resolution = json["resolution"] as? String ?: "720p",
            fps = (json["fps"] as? Number)?.toInt() ?: 15,
            audioEnabled = json["audioEnabled"] as? Boolean ?: true,
            talkBackEnabled = json["talkBackEnabled"] as? Boolean ?: false,
        )
    }
}

/** Corner placement for the burned-in clip timestamp. */
object ClipStampPosition {
    const val topLeft = "topLeft"
    const val topRight = "topRight"
    const val bottomLeft = "bottomLeft"
    const val bottomRight = "bottomRight"

    val values = listOf(topLeft, topRight, bottomLeft, bottomRight)

    fun label(value: String): String = when (value) {
        topLeft -> "Top left"
        topRight -> "Top right"
        bottomLeft -> "Bottom left"
        else -> "Bottom right"
    }
}

/** Privacy mask effect for exclusion zones in exported clips. */
object PrivacyMaskEffect {
    const val solid = "solid"
    const val pixelate = "pixelate"
    const val blur = "blur"

    val values = listOf(solid, pixelate, blur)

    fun label(value: String): String = when (value) {
        pixelate -> "Pixelate"
        blur -> "Blur"
        else -> "Solid dark"
    }
}

/** Notification video-preview constraints (Advanced settings). */
object VideoPreview {
    /** Frame-rate bounds; the preview decimates the clip to [DEFAULT_FPS] fps. */
    const val MIN_FPS = 1
    const val MAX_FPS = 5
    const val DEFAULT_FPS = 2

    /** Max frame width in px; height follows the clip's aspect ratio. */
    const val MIN_WIDTH = 160
    const val MAX_WIDTH = 640
    const val DEFAULT_WIDTH = 320

    /** Sample the first [MAX_DURATION_SECONDS] of the clip (whichever is shorter). */
    const val MAX_DURATION_SECONDS = 6

    /** Hard cap on sampled frames regardless of duration/fps (60 keeps 20s@5fps uncapped → 60, still <30s at 160px). */
    const val MAX_FRAMES = 60

    /**
     * Total encode pixel budget across ALL sampled frames (px). LZW on the
     * SM_A137F-class device runs at ~30 k px/s, so 60 frames at 320 px wide
     * (1.8 M px) blows the 60 s withTimeout. Keeping total ≤[PIXEL_BUDGET] px
     * shrinks the frame so the whole-video x-fps GIF actually lands under the
     * 60 s encode deadline; the user's maxWidthPx stays the ceiling for short
     * clips and only adapts down when sampleCount*fps would exceed it.
     */
    const val PIXEL_BUDGET = 700_000

    fun clampFps(value: Int): Int = value.coerceIn(MIN_FPS, MAX_FPS)
    fun clampWidth(value: Int): Int = value.coerceIn(MIN_WIDTH, MAX_WIDTH)
}

enum class PreviewMode { VIDEO, SHEET }

/** Cloud backup settings (WebDAV / S3-compatible; see backup/ design doc). */
data class CloudBackupSettings(
    val enabled: Boolean = false,
    val backend: String = "webdav",        // "webdav" | "s3"
    val serverUrl: String = "",            // WebDAV base URL or S3 endpoint
    val bucketOrPath: String = "",         // S3 bucket or WebDAV remote dir
    val region: String = "",               // S3 only
    val username: String = "",             // WebDAV user | S3 access key id
    val password: String = "",             // → SecretStore (SECRET_FIELD)
    val backupClips: Boolean = true,
    val backupSnapshots: Boolean = true,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "backend" to backend,
        "serverUrl" to serverUrl,
        "bucketOrPath" to bucketOrPath,
        "region" to region,
        "username" to username,
        "backupClips" to backupClips,
        "backupSnapshots" to backupSnapshots,
    )

    companion object {
        const val SECRET_FIELD = "password"

        fun fromJson(json: Map<String, Any?>): CloudBackupSettings = CloudBackupSettings(
            enabled = json["enabled"] as? Boolean ?: false,
            backend = json["backend"] as? String ?: "webdav",
            serverUrl = json["serverUrl"] as? String ?: "",
            bucketOrPath = json["bucketOrPath"] as? String ?: "",
            region = json["region"] as? String ?: "",
            username = json["username"] as? String ?: "",
            password = json["password"] as? String ?: "",
            backupClips = json["backupClips"] as? Boolean ?: true,
            backupSnapshots = json["backupSnapshots"] as? Boolean ?: true,
        )
    }
}

/**
 * App settings. Keeps the same JSON keys so
 * the stored blob shape matches the Dart reference.
 */
data class AppSettings(
    val cameraName: String = "Hallway",
    val cameraId: String = "0",
    val detectorConfigs: Map<String, DetectorConfig> = emptyMap(),
    val channelConfigs: List<ChannelConfig> = emptyList(),
    val notificationMergeWindow: Duration = Duration.ofSeconds(15),
    val retentionDays: Int = 7,
    val preRollSeconds: Int = 5,
    val postRollSeconds: Int = 5,
    val recordVideo: Boolean = true,
    val videoQuality: String = VideoQuality.lowest,
    /** Burn a date/time stamp into recorded clips. */
    val clipTimestamp: Boolean = false,
    /** One of [ClipStampPosition] values. */
    val clipTimestampPosition: String = ClipStampPosition.bottomRight,
    /** Include the camera name in the burned stamp text. */
    val clipTimestampCameraName: Boolean = false,
    /** Mask exclusion zones in exported clips (opaque overlay over private areas). */
    val privacyMasking: Boolean = false,
    /** One of [PrivacyMaskEffect] values: "solid", "pixelate", "blur". */
    val privacyMaskEffect: String = PrivacyMaskEffect.solid,
    val analysisResolution: String = AnalysisResolution.balanced,
    /** One of [DetectionSpeed] values: how often the gated ML pass may run. */
    val detectionSpeed: String = DetectionSpeed.accuracy,
    /** Monitor screen: bind the Preview use case (live image) while monitoring. */
    val monitorPreview: Boolean = false,
    val detectionZones: List<DetectionZone> = emptyList(),
    val exclusionZones: List<DetectionZone> = emptyList(),
    val scheduleExclusions: List<ScheduleWindow> = emptyList(),
    val knownFaces: List<KnownFace> = emptyList(),
    val tripwireZones: List<DetectionZone> = emptyList(),
    val liveView: LiveViewSettings = LiveViewSettings(),
    val cloudBackup: CloudBackupSettings = CloudBackupSettings(),
    val previewMode: PreviewMode = PreviewMode.VIDEO,
    /** Frame rate for notification video-previews (clamped to [VideoPreview]). */
    val previewFps: Int = VideoPreview.DEFAULT_FPS,
    /** Max frame width in px for notification video-previews. */
    val previewMaxWidthPx: Int = VideoPreview.DEFAULT_WIDTH,
    /**
     * One-way flag: the pre-2026-08-23 legacy cooldown normalization has run.
     * Guards [SettingsStore.migrateLegacyCooldowns] so an intentional 60s /
     * 120s / 5min cooldown chosen after migration is never rewritten to 5s.
     */
    val cooldownsMigrated: Boolean = false,
    /**
     * One-way flag: the pre-2026-09-13 merge-window upgrade has run. Guards
     * [SettingsStore.migrateLegacyMergeWindow] so an intentional short window
     * chosen after the change is never bumped again.
     */
    val mergeWindowUpgraded: Boolean = false,
) {
    fun copyWith(
        cameraName: String? = null,
        cameraId: String? = null,
        detectorConfigs: Map<String, DetectorConfig>? = null,
        channelConfigs: List<ChannelConfig>? = null,
        notificationMergeWindow: Duration? = null,
        retentionDays: Int? = null,
        preRollSeconds: Int? = null,
        postRollSeconds: Int? = null,
        recordVideo: Boolean? = null,
        videoQuality: String? = null,
        clipTimestamp: Boolean? = null,
        clipTimestampPosition: String? = null,
        clipTimestampCameraName: Boolean? = null,
        privacyMasking: Boolean? = null,
        privacyMaskEffect: String? = null,
        analysisResolution: String? = null,
        detectionSpeed: String? = null,
        monitorPreview: Boolean? = null,
        detectionZones: List<DetectionZone>? = null,
        exclusionZones: List<DetectionZone>? = null,
        scheduleExclusions: List<ScheduleWindow>? = null,
        knownFaces: List<KnownFace>? = null,
        tripwireZones: List<DetectionZone>? = null,
        liveView: LiveViewSettings? = null,
        cloudBackup: CloudBackupSettings? = null,
        previewMode: PreviewMode? = null,
        previewFps: Int? = null,
        previewMaxWidthPx: Int? = null,
        cooldownsMigrated: Boolean? = null,
        mergeWindowUpgraded: Boolean? = null,
    ): AppSettings = AppSettings(
        cameraName = cameraName ?: this.cameraName,
        cameraId = cameraId ?: this.cameraId,
        detectorConfigs = detectorConfigs ?: this.detectorConfigs,
        channelConfigs = channelConfigs ?: this.channelConfigs,
        notificationMergeWindow = notificationMergeWindow ?: this.notificationMergeWindow,
        retentionDays = retentionDays ?: this.retentionDays,
        preRollSeconds = preRollSeconds ?: this.preRollSeconds,
        postRollSeconds = postRollSeconds ?: this.postRollSeconds,
        recordVideo = recordVideo ?: this.recordVideo,
        videoQuality = videoQuality ?: this.videoQuality,
        clipTimestamp = clipTimestamp ?: this.clipTimestamp,
        clipTimestampPosition = clipTimestampPosition ?: this.clipTimestampPosition,
        clipTimestampCameraName = clipTimestampCameraName ?: this.clipTimestampCameraName,
        privacyMasking = privacyMasking ?: this.privacyMasking,
        privacyMaskEffect = privacyMaskEffect ?: this.privacyMaskEffect,
        analysisResolution = analysisResolution ?: this.analysisResolution,
        detectionSpeed = detectionSpeed ?: this.detectionSpeed,
        monitorPreview = monitorPreview ?: this.monitorPreview,
        detectionZones = detectionZones ?: this.detectionZones,
        exclusionZones = exclusionZones ?: this.exclusionZones,
        scheduleExclusions = scheduleExclusions ?: this.scheduleExclusions,
        knownFaces = knownFaces ?: this.knownFaces,
        tripwireZones = tripwireZones ?: this.tripwireZones,
        liveView = liveView ?: this.liveView,
        cloudBackup = cloudBackup ?: this.cloudBackup,
        previewMode = previewMode ?: this.previewMode,
        previewFps = previewFps ?: this.previewFps,
        previewMaxWidthPx = previewMaxWidthPx ?: this.previewMaxWidthPx,
        cooldownsMigrated = cooldownsMigrated ?: this.cooldownsMigrated,
        mergeWindowUpgraded = mergeWindowUpgraded ?: this.mergeWindowUpgraded,
    )

    fun toJson(): Map<String, Any?> {
        val json = LinkedHashMap<String, Any?>()
        json["cameraName"] = cameraName
        json["cameraId"] = cameraId
        json["detectorConfigs"] = detectorConfigs.mapValues { it.value.toJson() }
        json["channelConfigs"] = channelConfigs.map { it.toJson() }
        json["notificationMergeWindowMs"] = notificationMergeWindow.toMillis()
        json["retentionDays"] = retentionDays
        json["preRollSeconds"] = preRollSeconds
        json["postRollSeconds"] = postRollSeconds
        json["recordVideo"] = recordVideo
        json["videoQuality"] = videoQuality
        json["clipTimestamp"] = clipTimestamp
        json["clipTimestampPosition"] = clipTimestampPosition
        json["clipTimestampCameraName"] = clipTimestampCameraName
        json["privacyMasking"] = privacyMasking
        json["privacyMaskEffect"] = privacyMaskEffect
        json["analysisResolution"] = analysisResolution
        json["detectionSpeed"] = detectionSpeed
        json["monitorPreview"] = monitorPreview
        json["detectionZones"] = detectionZones.map { it.toJson() }
        json["exclusionZones"] = exclusionZones.map { it.toJson() }
        json["knownFaces"] = knownFaces.map { it.toJson() }
        json["tripwireZones"] = tripwireZones.map { it.toJson() }
        json["scheduleExclusions"] = scheduleExclusions.map { it.toJson() }
        json["liveView"] = liveView.toJson()
        json["cloudBackup"] = cloudBackup.toJson()
        json["previewMode"] = previewMode.name
        json["previewFps"] = previewFps
        json["previewMaxWidthPx"] = previewMaxWidthPx
        json["cooldownsMigrated"] = cooldownsMigrated
        json["mergeWindowUpgraded"] = mergeWindowUpgraded
        return json
    }

    companion object {
        fun defaults(): AppSettings = AppSettings(
            cameraName = "Hallway",
            detectorConfigs = linkedMapOf(
                TriggerType.motion to DetectorConfig(
                    type = TriggerType.motion,
                    threshold = 0.03,
                    persistenceFrames = 2,
                    // Motion is the gate source for every vision detector and
                    // cannot be disabled (see fromJson force-on below).
                    enabled = true,
                ),
                TriggerType.babyCry to DetectorConfig(
                    type = TriggerType.babyCry,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.glassBreak to DetectorConfig(
                    type = TriggerType.glassBreak,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.loudNoise to DetectorConfig(
                    type = TriggerType.loudNoise,
                    threshold = 0.5,
                    persistenceFrames = 1,
                    enabled = false,
                ),
                TriggerType.face to DetectorConfig(
                    type = TriggerType.face,
                    threshold = 0.7,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.person to DetectorConfig(
                    type = TriggerType.person,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.tamper to DetectorConfig(
                    type = TriggerType.tamper,
                    threshold = 0.5,
                    persistenceFrames = 3,
                    cooldown = Duration.ofSeconds(5),
                    enabled = false,
                ),
                TriggerType.health to DetectorConfig(
                    type = TriggerType.health,
                    enabled = true,
                    cooldown = Duration.ofSeconds(5),
                ),
                TriggerType.dog to DetectorConfig(
                    type = TriggerType.dog,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.cat to DetectorConfig(
                    type = TriggerType.cat,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.vehicle to DetectorConfig(
                    type = TriggerType.vehicle,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.bird to DetectorConfig(
                    type = TriggerType.bird,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.livestock to DetectorConfig(
                    type = TriggerType.livestock,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
                TriggerType.loitering to DetectorConfig(
                    type = TriggerType.loitering,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    dwellSeconds = 10,
                ),
                TriggerType.tripwire to DetectorConfig(
                    type = TriggerType.tripwire,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                ),
            ),
            channelConfigs = listOf(
                ChannelConfig(id = "log", type = "log", enabled = true),
            ),
        )

        /** Default cosine-distance cutoff for known-face matching. */
        const val FACE_MATCH_THRESHOLD = 0.65

        /** Recognition is on iff its routing configs exist. */
        fun faceRecognitionEnabled(settings: AppSettings): Boolean =
            settings.detectorConfigs.containsKey(TriggerType.faceKnown)

        /**
         * Face-recognition migration: enabling ensures a live `face` detector
         * and seeds `face_known`/`face_unknown` routing configs (channels
         * copied from the face config); disabling removes them, leaving the
         * plain face detector untouched. Idempotent both ways.
         */
        fun AppSettings.withFaceRecognition(enabled: Boolean): AppSettings {
            val configs = detectorConfigs.toMutableMap()
            if (!enabled) {
                if (!configs.containsKey(TriggerType.faceKnown) &&
                    !configs.containsKey(TriggerType.faceUnknown)
                ) {
                    return this
                }
                configs.remove(TriggerType.faceKnown)
                configs.remove(TriggerType.faceUnknown)
                return copy(detectorConfigs = configs)
            }
            if (configs.containsKey(TriggerType.faceKnown)) return this
            val face = (configs[TriggerType.face] ?: defaultFaceConfig()).copy(enabled = true)
            configs[TriggerType.face] = face
            for (type in listOf(TriggerType.faceKnown, TriggerType.faceUnknown)) {
                configs[type] = face.copy(
                    type = type,
                    threshold = if (type == TriggerType.faceKnown) FACE_MATCH_THRESHOLD else face.threshold,
                    persistenceFrames = 1,
                )
            }
            return copy(detectorConfigs = configs)
        }

        /**
         * Single write path for per-detector config edits: non-face types are
         * a plain map replace; disabling the face detector also forces
         * recognition off, because recognition runs through the face detector
         * at runtime (registered under the `face` key) — recognition-on /
         * detection-off would be silently dead.
         */
        fun AppSettings.withDetectorConfig(next: DetectorConfig): AppSettings {
            val updated = copy(detectorConfigs = detectorConfigs + (next.type to next))
            return if (next.type == TriggerType.face && !next.enabled) {
                updated.withFaceRecognition(false)
            } else {
                updated
            }
        }

        private fun defaultFaceConfig(): DetectorConfig =
            DetectorConfig(
                type = TriggerType.face,
                threshold = 0.7,
                persistenceFrames = 2,
                enabled = false,
            )

        fun fromJson(json: Map<String, Any?>): AppSettings {
            val defaults = defaults()
            val detectors = (json["detectorConfigs"] as? Map<*, *>)
                ?.entries
                ?.associate { (k, v) ->
                    k as String to DetectorConfig.fromJson(v as Map<String, Any?>)
                }
            // Upgrade merge: stored values win for known types; types shipped
            // after the blob was written appear with their defaults so upgraded
            // installs see every detector (dog/cat/vehicle/loitering/...).
            val storedDetectors = detectors ?: emptyMap()
            val mergedDetectors = LinkedHashMap<String, DetectorConfig>()
            for ((type, def) in defaults.detectorConfigs) {
                val cfg = storedDetectors[type] ?: def
                // Motion gates every vision detector: a stored disabled flag
                // (from before the switch was removed) must not silently
                // starve all gated detectors.
                mergedDetectors[type] = if (type == TriggerType.motion) cfg.copy(enabled = true) else cfg
            }
            for ((type, cfg) in storedDetectors) {
                if (!mergedDetectors.containsKey(type)) mergedDetectors[type] = cfg
            }
            val stored = (json["channelConfigs"] as? List<*>)
                ?.mapNotNull { e ->
                    val config = ChannelConfig.fromJson(e as Map<String, Any?>)
                    // The retired on-device alarm channel is dropped outright:
                    // it has no UI, no delete path, and no secrets to migrate.
                    if (config.type == "siren") return@mapNotNull null
                    if (config.type != "discord") return@mapNotNull config
                    ChannelConfig(
                        id = config.id,
                        type = "webhook",
                        enabled = config.enabled,
                        settingsJson = mapOf("preset" to "discord") + config.settingsJson,
                    )
                }
                ?: emptyList()
            val channels = (stored + defaults.channelConfigs.filter { d ->
                stored.none { it.id == d.id }
            }).filterNot { it.isPristinePlaceholder() }
            val settings = AppSettings(
                cameraName = json["cameraName"] as? String ?: defaults.cameraName,
                cameraId = json["cameraId"] as? String ?: defaults.cameraId,
                detectorConfigs = mergedDetectors,
                channelConfigs = channels,
                notificationMergeWindow = Duration.ofMillis(
                    (json["notificationMergeWindowMs"] as? Number)?.toLong()
                        ?: defaults.notificationMergeWindow.toMillis(),
                ),
                retentionDays = (json["retentionDays"] as? Number)?.toInt()
                    ?: defaults.retentionDays,
                preRollSeconds = (json["preRollSeconds"] as? Number)?.toInt()
                    ?: defaults.preRollSeconds,
                postRollSeconds = (json["postRollSeconds"] as? Number)?.toInt()
                    ?: defaults.postRollSeconds,
                recordVideo = json["recordVideo"] as? Boolean ?: defaults.recordVideo,
                videoQuality = json["videoQuality"] as? String ?: defaults.videoQuality,
                clipTimestamp = json["clipTimestamp"] as? Boolean ?: defaults.clipTimestamp,
                clipTimestampPosition = json["clipTimestampPosition"] as? String
                    ?: defaults.clipTimestampPosition,
                clipTimestampCameraName = json["clipTimestampCameraName"] as? Boolean
                    ?: defaults.clipTimestampCameraName,
                privacyMasking = json["privacyMasking"] as? Boolean
                    ?: defaults.privacyMasking,
                privacyMaskEffect = json["privacyMaskEffect"] as? String
                    ?: defaults.privacyMaskEffect,
                analysisResolution = json["analysisResolution"] as? String
                    ?: defaults.analysisResolution,
                detectionSpeed = json["detectionSpeed"] as? String
                    ?: defaults.detectionSpeed,
                monitorPreview = json["monitorPreview"] as? Boolean
                    ?: defaults.monitorPreview,
                detectionZones = (json["detectionZones"] as? List<*>)
                    ?.map { DetectionZone.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                exclusionZones = (json["exclusionZones"] as? List<*>)
                    ?.map { DetectionZone.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                knownFaces = (json["knownFaces"] as? List<*>)
                    ?.map { KnownFace.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                tripwireZones = (json["tripwireZones"] as? List<*>)
                    ?.map { DetectionZone.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                scheduleExclusions = (json["scheduleExclusions"] as? List<*>)
                    ?.map { ScheduleWindow.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                liveView = (json["liveView"] as? Map<*, *>)
                    ?.let { LiveViewSettings.fromJson(it as Map<String, Any?>) }
                    ?: LiveViewSettings(),
                cloudBackup = (json["cloudBackup"] as? Map<*, *>)
                    ?.let { CloudBackupSettings.fromJson(it as Map<String, Any?>) }
                    ?: CloudBackupSettings(),
                previewMode = (json["previewMode"] as? String)?.let { runCatching { PreviewMode.valueOf(it) }.getOrNull() } ?: defaults.previewMode,
                previewFps = VideoPreview.clampFps(
                    (json["previewFps"] as? Number)?.toInt()
                        ?: (json["gifPreviewFps"] as? Number)?.toInt()
                        ?: defaults.previewFps,
                ),
                previewMaxWidthPx = VideoPreview.clampWidth(
                    (json["previewMaxWidthPx"] as? Number)?.toInt()
                        ?: (json["gifPreviewMaxWidthPx"] as? Number)?.toInt()
                        ?: defaults.previewMaxWidthPx,
                ),
                cooldownsMigrated = json["cooldownsMigrated"] as? Boolean ?: false,
                mergeWindowUpgraded = json["mergeWindowUpgraded"] as? Boolean ?: false,
            )
            // Legacy heal: recognition without a live face detector is a
            // silently-dead state (recognition runs under the face key).
            val faceEnabled = settings.detectorConfigs[TriggerType.face]?.enabled == true
            return if (!faceEnabled && faceRecognitionEnabled(settings)) {
                settings.withFaceRecognition(false)
            } else {
                settings
            }
        }
    }
}