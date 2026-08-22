package io.securitycam.level1.core

import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectorConfig
import java.time.Duration

/** Camera source choices (desktop dev-only; mobile always uses on-device camera). */
object CameraSource {
    const val simulated = "simulated"
    const val webcam = "webcam"
    const val file = "file"
}

/** Audio source choices (desktop dev-only; mobile always uses the microphone). */
object AudioInput {
    const val simulated = "simulated"
    const val mic = "mic"
    const val file = "file"
}

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

/** Screen orientation lock (Android only). */
object ScreenOrientation {
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

/**
 * App settings (port of `lib/core/settings.dart`). Keeps the same JSON keys so
 * the stored blob shape matches the Dart reference.
 */
data class AppSettings(
    val cameraName: String = "Hallway",
    val cameraSource: String = CameraSource.simulated,
    val cameraSourcePath: String? = null,
    val audioSource: String = AudioInput.simulated,
    val audioSourcePath: String? = null,
    val detectorConfigs: Map<String, DetectorConfig> = emptyMap(),
    val channelConfigs: List<ChannelConfig> = emptyList(),
    val notificationMergeWindow: Duration = Duration.ofSeconds(3),
    val retentionDays: Int = 7,
    val preRollSeconds: Int = 5,
    val postRollSeconds: Int = 5,
    val recordVideo: Boolean = true,
    val videoQuality: String = VideoQuality.lowest,
    val analysisResolution: String = AnalysisResolution.balanced,
    val screenOrientation: String = ScreenOrientation.portrait,
    val detectionRegions: List<DetectionRegion> = emptyList(),
    val exclusionRegions: List<DetectionRegion> = emptyList(),
    val scheduleExclusions: List<ScheduleWindow> = emptyList(),
    val knownFaces: List<KnownFace> = emptyList(),
) {
    fun copyWith(
        cameraName: String? = null,
        cameraSource: String? = null,
        cameraSourcePath: String? = null,
        clearCameraSourcePath: Boolean = false,
        audioSource: String? = null,
        audioSourcePath: String? = null,
        clearAudioSourcePath: Boolean = false,
        detectorConfigs: Map<String, DetectorConfig>? = null,
        channelConfigs: List<ChannelConfig>? = null,
        notificationMergeWindow: Duration? = null,
        retentionDays: Int? = null,
        preRollSeconds: Int? = null,
        postRollSeconds: Int? = null,
        recordVideo: Boolean? = null,
        videoQuality: String? = null,
        analysisResolution: String? = null,
        screenOrientation: String? = null,
        detectionRegions: List<DetectionRegion>? = null,
        exclusionRegions: List<DetectionRegion>? = null,
        knownFaces: List<KnownFace>? = null,
    ): AppSettings = AppSettings(
        cameraName = cameraName ?: this.cameraName,
        cameraSource = cameraSource ?: this.cameraSource,
        cameraSourcePath = if (clearCameraSourcePath) null else cameraSourcePath ?: this.cameraSourcePath,
        audioSource = audioSource ?: this.audioSource,
        audioSourcePath = if (clearAudioSourcePath) null else audioSourcePath ?: this.audioSourcePath,
        detectorConfigs = detectorConfigs ?: this.detectorConfigs,
        channelConfigs = channelConfigs ?: this.channelConfigs,
        notificationMergeWindow = notificationMergeWindow ?: this.notificationMergeWindow,
        retentionDays = retentionDays ?: this.retentionDays,
        preRollSeconds = preRollSeconds ?: this.preRollSeconds,
        postRollSeconds = postRollSeconds ?: this.postRollSeconds,
        recordVideo = recordVideo ?: this.recordVideo,
        videoQuality = videoQuality ?: this.videoQuality,
        analysisResolution = analysisResolution ?: this.analysisResolution,
        screenOrientation = screenOrientation ?: this.screenOrientation,
        detectionRegions = detectionRegions ?: this.detectionRegions,
        exclusionRegions = exclusionRegions ?: this.exclusionRegions,
        scheduleExclusions = scheduleExclusions ?: this.scheduleExclusions,
        knownFaces = knownFaces ?: this.knownFaces,
    )

    fun toJson(): Map<String, Any?> {
        val json = LinkedHashMap<String, Any?>()
        json["cameraName"] = cameraName
        json["cameraSource"] = cameraSource
        cameraSourcePath?.let { json["cameraSourcePath"] = it }
        json["audioSource"] = audioSource
        audioSourcePath?.let { json["audioSourcePath"] = it }
        json["detectorConfigs"] = detectorConfigs.mapValues { it.value.toJson() }
        json["channelConfigs"] = channelConfigs.map { it.toJson() }
        json["notificationMergeWindowMs"] = notificationMergeWindow.toMillis()
        json["retentionDays"] = retentionDays
        json["preRollSeconds"] = preRollSeconds
        json["postRollSeconds"] = postRollSeconds
        json["recordVideo"] = recordVideo
        json["videoQuality"] = videoQuality
        json["analysisResolution"] = analysisResolution
        json["screenOrientation"] = screenOrientation
        json["detectionRegions"] = detectionRegions.map { it.toJson() }
        json["exclusionRegions"] = exclusionRegions.map { it.toJson() }
        json["knownFaces"] = knownFaces.map { it.toJson() }
        json["scheduleExclusions"] = scheduleExclusions.map { it.toJson() }
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
                    cooldown = Duration.ofSeconds(120),
                    enabled = false,
                    routeToChannelIds = listOf("telegram"),
                ),
                TriggerType.health to DetectorConfig(
                    type = TriggerType.health,
                    enabled = true,
                    cooldown = Duration.ofMinutes(5),
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

        fun fromJson(json: Map<String, Any?>): AppSettings {
            val defaults = defaults()
            val detectors = (json["detectorConfigs"] as? Map<*, *>)
                ?.entries
                ?.associate { (k, v) ->
                    k as String to DetectorConfig.fromJson(v as Map<String, Any?>)
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
                cameraSource = json["cameraSource"] as? String ?: defaults.cameraSource,
                cameraSourcePath = json["cameraSourcePath"] as? String,
                audioSource = json["audioSource"] as? String ?: defaults.audioSource,
                audioSourcePath = json["audioSourcePath"] as? String,
                detectorConfigs = detectors ?: defaults.detectorConfigs,
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
                analysisResolution = json["analysisResolution"] as? String
                    ?: defaults.analysisResolution,
                screenOrientation = json["screenOrientation"] as? String
                    ?: defaults.screenOrientation,
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
            )
        }
    }
}