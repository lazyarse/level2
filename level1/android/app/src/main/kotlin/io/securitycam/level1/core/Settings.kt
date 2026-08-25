package io.securitycam.level1.core

import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectorConfig
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
    )

    companion object {
        const val SECRET_FIELD = "password"

        fun fromJson(json: Map<String, Any?>): LiveViewSettings = LiveViewSettings(
            enabled = json["enabled"] as? Boolean ?: false,
            mode = json["mode"] as? String ?: "server",
            port = (json["port"] as? Number)?.toInt() ?: 554,
            username = json["username"] as? String ?: "",
            password = json["password"] as? String ?: "",
            relayUrl = json["relayUrl"] as? String ?: "",
            resolution = json["resolution"] as? String ?: "720p",
            fps = (json["fps"] as? Number)?.toInt() ?: 15,
            audioEnabled = json["audioEnabled"] as? Boolean ?: true,
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

/** Screen orientation lock (Android only). */object ScreenOrientation {
    const val portrait = "portrait"
    const val landscape = "landscape"
    const val sensor = "sensor"

    val values = listOf(portrait, landscape, sensor)

    fun label(value: String): String = when (value) {
        landscape -> "Landscape"
        sensor -> "Auto (sensor)"
        else -> "Portrait"
    }
}

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
 * App settings (port of `lib/core/settings.dart`). Keeps the same JSON keys so
 * the stored blob shape matches the Dart reference.
 */
data class AppSettings(
    val cameraName: String = "Hallway",
    val cameraId: String = "0",
    val detectorConfigs: Map<String, DetectorConfig> = emptyMap(),
    val channelConfigs: List<ChannelConfig> = emptyList(),
    val notificationMergeWindow: Duration = Duration.ofSeconds(3),
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
    val screenOrientation: String = ScreenOrientation.portrait,
    /** Monitor screen: bind the Preview use case (live image) while monitoring. */
    val monitorPreview: Boolean = true,
    val detectionRegions: List<DetectionRegion> = emptyList(),
    val exclusionRegions: List<DetectionRegion> = emptyList(),
    val scheduleExclusions: List<ScheduleWindow> = emptyList(),
    val knownFaces: List<KnownFace> = emptyList(),
    val liveView: LiveViewSettings = LiveViewSettings(),
    val cloudBackup: CloudBackupSettings = CloudBackupSettings(),
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
        screenOrientation: String? = null,
        detectionRegions: List<DetectionRegion>? = null,
        exclusionRegions: List<DetectionRegion>? = null,
        scheduleExclusions: List<ScheduleWindow>? = null,
        knownFaces: List<KnownFace>? = null,
        liveView: LiveViewSettings? = null,
        cloudBackup: CloudBackupSettings? = null,
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
        screenOrientation = screenOrientation ?: this.screenOrientation,
        monitorPreview = monitorPreview ?: this.monitorPreview,
        detectionRegions = detectionRegions ?: this.detectionRegions,
        exclusionRegions = exclusionRegions ?: this.exclusionRegions,
        scheduleExclusions = scheduleExclusions ?: this.scheduleExclusions,
        knownFaces = knownFaces ?: this.knownFaces,        liveView = liveView ?: this.liveView,
        cloudBackup = cloudBackup ?: this.cloudBackup,
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
        json["screenOrientation"] = screenOrientation
        json["monitorPreview"] = monitorPreview
        json["detectionRegions"] = detectionRegions.map { it.toJson() }
        json["exclusionRegions"] = exclusionRegions.map { it.toJson() }
        json["knownFaces"] = knownFaces.map { it.toJson() }
        json["scheduleExclusions"] = scheduleExclusions.map { it.toJson() }
        json["liveView"] = liveView.toJson()
        json["cloudBackup"] = cloudBackup.toJson()
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
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.babyCry to DetectorConfig(
                    type = TriggerType.babyCry,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.glassBreak to DetectorConfig(
                    type = TriggerType.glassBreak,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.loudNoise to DetectorConfig(
                    type = TriggerType.loudNoise,
                    threshold = 0.5,
                    persistenceFrames = 1,
                    enabled = false,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.face to DetectorConfig(
                    type = TriggerType.face,
                    threshold = 0.7,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.person to DetectorConfig(
                    type = TriggerType.person,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.tamper to DetectorConfig(
                    type = TriggerType.tamper,
                    threshold = 0.5,
                    persistenceFrames = 3,
                    cooldown = Duration.ofSeconds(5),
                    enabled = false,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.health to DetectorConfig(
                    type = TriggerType.health,
                    enabled = true,
                    cooldown = Duration.ofSeconds(5),
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.dog to DetectorConfig(
                    type = TriggerType.dog,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.cat to DetectorConfig(
                    type = TriggerType.cat,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.vehicle to DetectorConfig(
                    type = TriggerType.vehicle,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.bird to DetectorConfig(
                    type = TriggerType.bird,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.livestock to DetectorConfig(
                    type = TriggerType.livestock,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.loitering to DetectorConfig(
                    type = TriggerType.loitering,
                    threshold = 0.5,
                    persistenceFrames = 2,
                    enabled = false,
                    motionGated = true,
                    dwellSeconds = 10,
                    routeToChannelIds = listOf("telegram"),
                ),
            ),
            channelConfigs = listOf(
                ChannelConfig(id = "log", type = "log", enabled = true),
                ChannelConfig(id = "telegram", type = "telegram", enabled = false),
                ChannelConfig(id = "email", type = "email", enabled = false),
                ChannelConfig(
                    id = "discord",
                    type = "webhook",
                    settingsJson = mapOf("preset" to "discord"),
                    enabled = false,
                ),
                ChannelConfig(id = "pushover", type = "pushover", enabled = false),
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

        private fun defaultFaceConfig(): DetectorConfig =
            DetectorConfig(
                type = TriggerType.face,
                threshold = 0.7,
                persistenceFrames = 2,
                enabled = false,
                motionGated = true,
                routeToChannelIds = listOf("telegram"),
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
                mergedDetectors[type] = storedDetectors[type] ?: def
            }
            for ((type, cfg) in storedDetectors) {
                if (!mergedDetectors.containsKey(type)) mergedDetectors[type] = cfg
            }
            val stored = (json["channelConfigs"] as? List<*>)
                ?.mapNotNull { e ->
                    val config = ChannelConfig.fromJson(e as Map<String, Any?>)
                    if (config.type != "discord") return@mapNotNull config
                    ChannelConfig(
                        id = config.id,
                        type = "webhook",
                        enabled = config.enabled,
                        settingsJson = mapOf("preset" to "discord") + config.settingsJson,
                    )
                }
                ?: emptyList()
            val channels = stored + defaults.channelConfigs.filter { d ->
                stored.none { it.id == d.id }
            }
            return AppSettings(
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
                screenOrientation = json["screenOrientation"] as? String
                    ?: defaults.screenOrientation,
                monitorPreview = json["monitorPreview"] as? Boolean
                    ?: defaults.monitorPreview,
                detectionRegions = (json["detectionRegions"] as? List<*>)
                    ?.map { DetectionRegion.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                exclusionRegions = (json["exclusionRegions"] as? List<*>)
                    ?.map { DetectionRegion.fromJson(it as Map<String, Any?>) }
                    ?: emptyList(),
                knownFaces = (json["knownFaces"] as? List<*>)
                    ?.map { KnownFace.fromJson(it as Map<String, Any?>) }
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
            )
        }
    }
}