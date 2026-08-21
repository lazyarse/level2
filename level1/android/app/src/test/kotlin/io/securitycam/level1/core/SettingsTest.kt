package io.securitycam.level1.core

import io.securitycam.level1.detection.DetectionRegion
import io.securitycam.level1.detection.DetectorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Port of `test/settings_test.dart`. */
class SettingsTest {

    private fun jsonOf(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf(*pairs)

    @Test
    fun defaultsContainMotionBabyCryGlassBreakDetectorsAndLogChannel() {
        val settings = AppSettings.defaults()
        assertEquals("Hallway", settings.cameraName)
        assertEquals(CameraSource.simulated, settings.cameraSource)
        assertNull(settings.cameraSourcePath)
        assertEquals(AudioInput.simulated, settings.audioSource)
        assertNull(settings.audioSourcePath)
        assertTrue(settings.detectorConfigs.keys.containsAll(listOf("motion", "baby_cry", "glass_break")))
        assertTrue(settings.detectorConfigs[TriggerType.motion]!!.routeToChannelIds.contains("telegram"))
        assertTrue(settings.channelConfigs.any { it.id == "log" })
    }

    @Test
    fun jsonRoundTripPreservesSettings() {
        val settings = AppSettings.defaults().copyWith(
            cameraSource = CameraSource.webcam,
            cameraSourcePath = "/dev/video0",
            audioSource = AudioInput.file,
            audioSourcePath = "/tmp/a.wav",
        )
        val restored = AppSettings.fromJson(settings.toJson())
        assertEquals(settings.cameraName, restored.cameraName)
        assertEquals(CameraSource.webcam, restored.cameraSource)
        assertEquals("/dev/video0", restored.cameraSourcePath)
        assertEquals(AudioInput.file, restored.audioSource)
        assertEquals("/tmp/a.wav", restored.audioSourcePath)
        assertEquals(settings.detectorConfigs.keys, restored.detectorConfigs.keys)
        assertEquals(
            settings.detectorConfigs[TriggerType.motion]!!.threshold,
            restored.detectorConfigs[TriggerType.motion]!!.threshold,
            0.0,
        )
        assertEquals(
            settings.detectorConfigs[TriggerType.motion]!!.cooldown,
            restored.detectorConfigs[TriggerType.motion]!!.cooldown,
        )
        assertEquals(settings.channelConfigs.size, restored.channelConfigs.size)
        assertEquals(settings.notificationMergeWindow, restored.notificationMergeWindow)
        assertEquals(settings.preRollSeconds, restored.preRollSeconds)
        assertEquals(settings.postRollSeconds, restored.postRollSeconds)
    }

    @Test
    fun videoClipSettingsRoundTripAndDefaultTo55RecordOn() {
        val defaults = AppSettings.defaults()
        assertEquals(5, defaults.preRollSeconds)
        assertEquals(5, defaults.postRollSeconds)
        assertTrue(defaults.recordVideo)
        assertEquals(VideoQuality.lowest, defaults.videoQuality)

        val custom = defaults.copyWith(
            preRollSeconds = 8,
            postRollSeconds = 12,
            recordVideo = false,
            videoQuality = VideoQuality.fhd,
        )
        val restored = AppSettings.fromJson(custom.toJson())
        assertEquals(8, restored.preRollSeconds)
        assertEquals(12, restored.postRollSeconds)
        assertFalse(restored.recordVideo)
        assertEquals(VideoQuality.fhd, restored.videoQuality)
    }

    @Test
    fun oldJsonWithoutClipFieldsFallsBackTo55RecordOn() {
        val restored = AppSettings.fromJson(jsonOf("cameraName" to "Nursery"))
        assertEquals(5, restored.preRollSeconds)
        assertEquals(5, restored.postRollSeconds)
        assertTrue(restored.recordVideo)
        assertEquals(VideoQuality.lowest, restored.videoQuality)
    }

    @Test
    fun videoQualityLabelsQuantifyEachTier() {
        assertEquals(listOf("lowest", "sd", "hd", "fhd", "uhd", "highest"), VideoQuality.values)
        assertEquals("Lowest (device minimum)", VideoQuality.label("lowest"))
        assertEquals("SD (480p)", VideoQuality.label("sd"))
        assertEquals("HD (720p)", VideoQuality.label("hd"))
        assertEquals("Full HD (1080p)", VideoQuality.label("fhd"))
        assertEquals("UHD (4K)", VideoQuality.label("uhd"))
        assertEquals("Highest (device maximum)", VideoQuality.label("highest"))
        assertEquals("Lowest (device minimum)", VideoQuality.label("bogus"))
    }

    @Test
    fun emptyJsonFallsBackToDefaults() {
        val restored = AppSettings.fromJson(emptyMap())
        assertEquals("Hallway", restored.cameraName)
        assertEquals(CameraSource.simulated, restored.cameraSource)
        assertNull(restored.cameraSourcePath)
        assertEquals(AudioInput.simulated, restored.audioSource)
        assertNull(restored.audioSourcePath)
        assertTrue(restored.detectorConfigs.isNotEmpty())
    }

    @Test
    fun oldJsonWithoutSourceFieldsFallsBackToSimulated() {
        val restored = AppSettings.fromJson(jsonOf("cameraName" to "Nursery"))
        assertEquals("Nursery", restored.cameraName)
        assertEquals(CameraSource.simulated, restored.cameraSource)
        assertEquals(AudioInput.simulated, restored.audioSource)
        assertNull(restored.cameraSourcePath)
        assertNull(restored.audioSourcePath)
    }

    @Test
    fun legacyJsonWithOnlyLogChannelMergesInDefaultChannels() {
        val restored = AppSettings.fromJson(
            jsonOf(
                "channelConfigs" to listOf(
                    mapOf("id" to "log", "type" to "log", "enabled" to true),
                ),
            ),
        )
        assertEquals(
            listOf("log", "telegram", "email", "discord", "pushover"),
            restored.channelConfigs.map { it.id },
        )
        assertTrue(restored.channelConfigs.first { it.id == "log" }.enabled)
        for (c in restored.channelConfigs.filter { it.id != "log" }) {
            assertFalse("merged channels default to disabled", c.enabled)
        }
    }

    @Test
    fun defaultsRetypeTheDiscordChannelToADisabledWebhookPreset() {
        val defaults = AppSettings.defaults()
        val discord = defaults.channelConfigs.first { it.id == "discord" }
        assertEquals("webhook", discord.type)
        assertEquals("discord", discord.settingsJson["preset"])
        assertFalse(discord.enabled)
        val pushover = defaults.channelConfigs.first { it.id == "pushover" }
        assertEquals("pushover", pushover.type)
        assertFalse(pushover.enabled)
    }

    @Test
    fun legacyDiscordChannelsMigrateToWebhookPresetDiscord() {
        val restored = AppSettings.fromJson(
            jsonOf(
                "channelConfigs" to listOf(
                    mapOf(
                        "id" to "discord",
                        "type" to "discord",
                        "enabled" to true,
                        "settings" to mapOf("webhookUrl" to "https://discord.com/api/webhooks/1/abc"),
                    ),
                ),
            ),
        )
        val discord = restored.channelConfigs.first { it.id == "discord" }
        assertEquals("webhook", discord.type)
        assertTrue(discord.enabled)
        assertEquals("discord", discord.settingsJson["preset"])
        assertEquals("https://discord.com/api/webhooks/1/abc", discord.settingsJson["webhookUrl"])
    }

    @Test
    fun webhookAndPushoverChannelSettingsJsonRoundTrip() {
        val settings = AppSettings.defaults().copyWith(
            channelConfigs = listOf(
                ChannelConfig(id = "log", type = "log", enabled = true),
                ChannelConfig(
                    id = "discord",
                    type = "webhook",
                    settingsJson = mapOf(
                        "preset" to "ntfy",
                        "url" to "https://ntfy.sh/cam",
                        "bearerToken" to "tok",
                        "title" to "Cam",
                        "bodyStyle" to "text",
                    ),
                ),
                ChannelConfig(
                    id = "pushover",
                    type = "pushover",
                    settingsJson = mapOf(
                        "appToken" to "a",
                        "userKey" to "u",
                        "sound" to "siren",
                        "priority" to 1,
                    ),
                ),
            ),
        )
        val restored = AppSettings.fromJson(settings.toJson())
        val webhook = restored.channelConfigs.first { it.id == "discord" }
        assertEquals("ntfy", webhook.settingsJson["preset"])
        assertEquals("https://ntfy.sh/cam", webhook.settingsJson["url"])
        val pushover = restored.channelConfigs.first { it.id == "pushover" }
        assertEquals("a", pushover.settingsJson["appToken"])
        assertEquals(1, pushover.settingsJson["priority"])
    }

    @Test
    fun storedChannelSettingsArePreservedNotClobberedByDefaults() {
        val restored = AppSettings.fromJson(
            jsonOf(
                "channelConfigs" to listOf(
                    mapOf("id" to "log", "type" to "log", "enabled" to true, "settings" to emptyMap<String, Any?>()),
                    mapOf(
                        "id" to "email",
                        "type" to "email",
                        "enabled" to true,
                        "settings" to mapOf("host" to "smtp.example.com", "port" to 587),
                    ),
                ),
            ),
        )
        val email = restored.channelConfigs.first { it.id == "email" }
        assertTrue(email.enabled)
        assertEquals("smtp.example.com", email.settingsJson["host"])
        assertTrue(restored.channelConfigs.map { it.id }.containsAll(listOf("log", "telegram", "email", "discord", "pushover")))
        assertEquals(5, restored.channelConfigs.size)
    }

    @Test
    fun copyWithUpdatesOnlyProvidedFields() {
        val settings = AppSettings.defaults()
        val updated = settings.copyWith(cameraName = "Nursery")
        assertEquals("Nursery", updated.cameraName)
        assertEquals(settings.detectorConfigs, updated.detectorConfigs)
        assertEquals(settings.cameraSource, updated.cameraSource)
    }

    @Test
    fun copyWithClearsTheSourcePathWhenSwitchingBackToSimulated() {
        val settings = AppSettings.defaults().copyWith(
            cameraSource = CameraSource.webcam,
            cameraSourcePath = "/dev/video0",
        )
        val restored = settings.copyWith(
            cameraSource = CameraSource.simulated,
            clearCameraSourcePath = true,
        )
        assertEquals(CameraSource.simulated, restored.cameraSource)
        assertNull(restored.cameraSourcePath)
    }

    @Test
    fun copyWithClearsTheAudioPathWhenSwitchingBackToSimulated() {
        val settings = AppSettings.defaults().copyWith(
            audioSource = AudioInput.file,
            audioSourcePath = "/tmp/a.wav",
        )
        val restored = settings.copyWith(
            audioSource = AudioInput.simulated,
            clearAudioSourcePath = true,
        )
        assertEquals(AudioInput.simulated, restored.audioSource)
        assertNull(restored.audioSourcePath)
    }

    @Test
    fun toJsonOmitsNullPaths() {
        val json = AppSettings.defaults().toJson()
        assertFalse(json.containsKey("cameraSourcePath"))
        assertFalse(json.containsKey("audioSourcePath"))
    }

    @Test
    fun analysisResolutionDefaultsToBalanced() {
        val s = AppSettings.defaults()
        assertEquals(AnalysisResolution.balanced, s.analysisResolution)
        val (w, h) = AnalysisResolution.size(s.analysisResolution)
        assertEquals(320 to 240, w to h)
    }

    @Test
    fun analysisResolutionJsonRoundTrips() {
        val s = AppSettings.defaults().copyWith(analysisResolution = AnalysisResolution.high)
        val back = AppSettings.fromJson(s.toJson())
        assertEquals(AnalysisResolution.high, back.analysisResolution)
    }

    @Test
    fun missingAnalysisResolutionFallsBackToBalanced() {
        val back = AppSettings.fromJson(emptyMap())
        assertEquals(AnalysisResolution.balanced, back.analysisResolution)
    }

    @Test
    fun presetLabels() {
        assertEquals("Low (160x120)", AnalysisResolution.label(AnalysisResolution.low))
        assertEquals("Balanced (320x240)", AnalysisResolution.label(AnalysisResolution.balanced))
        assertEquals("High (640x480)", AnalysisResolution.label(AnalysisResolution.high))
    }

    @Test
    fun defaultsIncludeAFaceDetectorDisabledAndMotionGated() {
        val s = AppSettings.defaults()
        val face = s.detectorConfigs[TriggerType.face]
        assertEquals(face != null, true)
        assertEquals(false, face!!.enabled)
        assertEquals(true, face.motionGated)
        assertEquals(0.7, face.threshold, 0.0)
    }

    @Test
    fun defaultsIncludeAPersonDetectorDisabledAndMotionGated() {
        val s = AppSettings.defaults()
        val person = s.detectorConfigs[TriggerType.person]
        assertEquals(person != null, true)
        assertEquals(false, person!!.enabled)
        assertEquals(true, person.motionGated)
        assertEquals(0.5, person.threshold, 0.0)
        assertTrue(s.detectorConfigs.containsKey(TriggerType.person))
    }

    @Test
    fun personConfigJsonRoundTrips() {
        val settings = AppSettings.defaults().copyWith(
            detectorConfigs = AppSettings.defaults().detectorConfigs + (
                TriggerType.person to DetectorConfig(
                    type = TriggerType.person,
                    threshold = 0.3,
                    persistenceFrames = 3,
                    enabled = true,
                    motionGated = true,
                    routeToChannelIds = listOf("telegram"),
                )
            ),
        )
        val restored = AppSettings.fromJson(settings.toJson())
        val person = restored.detectorConfigs[TriggerType.person]!!
        assertEquals(true, person.enabled)
        assertEquals(true, person.motionGated)
        assertEquals(0.3, person.threshold, 0.0)
        assertEquals(3, person.persistenceFrames)
    }

    @Test
    fun detectionRegionsDefaultToEmpty() {
        val s = AppSettings.defaults()
        assertEquals(emptyList<DetectionRegion>(), s.detectionRegions)
    }

    @Test
    fun detectionRegionsJsonRoundTripRectPlusPoly() {
        val s = AppSettings.defaults().copyWith(
            detectionRegions = listOf(
                DetectionRegion("r1", "rect", "doorway", listOf(0.1, 0.2, 0.5, 0.8)),
                DetectionRegion("p1", "poly", "driveway", listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8)),
            ),
        )
        val back = AppSettings.fromJson(s.toJson())
        assertEquals(2, back.detectionRegions.size)
        assertEquals("doorway", back.detectionRegions[0].label)
        assertEquals(listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6, 0.4, 0.8), back.detectionRegions[1].points)
    }

    @Test
    fun oldJsonWithoutDetectionRegionsFallsBackToEmpty() {
        val back = AppSettings.fromJson(emptyMap())
        assertEquals(emptyList<DetectionRegion>(), back.detectionRegions)
    }

    @Test
    fun exclusionRegionsDefaultToEmpty() {
        val s = AppSettings.defaults()
        assertEquals(emptyList<DetectionRegion>(), s.exclusionRegions)
    }

    @Test
    fun exclusionRegionsJsonRoundTrip() {
        val s = AppSettings.defaults().copyWith(
            detectionRegions = listOf(DetectionRegion("r1", "rect", "doorway", listOf(0.1, 0.2, 0.5, 0.8))),
            exclusionRegions = listOf(
                DetectionRegion("e1", "poly", "privacy", listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6)),
            ),
        )
        val back = AppSettings.fromJson(s.toJson())
        assertEquals(1, back.exclusionRegions.size)
        assertEquals("privacy", back.exclusionRegions[0].label)
        assertEquals(listOf(0.5, 0.2, 0.8, 0.3, 0.9, 0.6), back.exclusionRegions[0].points)
        assertEquals(1, back.detectionRegions.size)
    }

    @Test
    fun oldJsonWithoutExclusionRegionsFallsBackToEmpty() {
        val legacy = AppSettings.defaults()
            .copyWith(detectionRegions = listOf(DetectionRegion("r1", "rect", "doorway", listOf(0.1, 0.2, 0.5, 0.8))))
            .toJson()
            .toMutableMap()
        legacy.remove("exclusionRegions")
        val back = AppSettings.fromJson(legacy)
        assertEquals(emptyList<DetectionRegion>(), back.exclusionRegions)
        assertEquals(1, back.detectionRegions.size)
    }

    @Test
    fun notificationMergeWindowDefaultIsThreeSeconds() {
        val s = AppSettings.defaults()
        assertEquals(Duration.ofSeconds(3), s.notificationMergeWindow)
    }
}