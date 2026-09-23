package io.securitycam.level2.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.securitycam.level2.BuildConfig
import io.securitycam.level2.channels.EmailChannelSettings
import io.securitycam.level2.channels.PushoverChannelSettings
import io.securitycam.level2.channels.WebhookChannelSettings
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.AppSettings.Companion.withFaceRecognition
import io.securitycam.level2.core.ChannelConfig
import io.securitycam.level2.core.KnownFace
import io.securitycam.level2.core.TriggerType
import io.securitycam.level2.detection.DetectorConfig
import io.securitycam.level2.identity.KnownFaceStore
import java.io.File
import java.time.Duration
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Port of `test/settings_screen_test.dart` (draft/commit + save behavior). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class Harness(
        initial: AppSettings = AppSettings.defaults(),
        application: android.app.Application? = null,
    ) {
        val saved = mutableListOf<AppSettings>()
        val cleared = mutableListOf<Duration?>()
        val viewModel = SettingsViewModel(
            application = application,
            settingsLoader = { initial },
            settingsSaver = { saved.add(it) },
            eventsClearer = { cleared.add(it) },
        )
    }

    /** Harness seeded with the pre-empty-defaults channel set (log + four accounts). */
    private fun channelsHarness(): Harness = Harness(
        AppSettings.defaults().copyWith(
            channelConfigs = listOf(
                ChannelConfig(id = "log", type = "log", enabled = true),
                ChannelConfig(id = "telegram", type = "telegram", enabled = false),
                ChannelConfig(id = "email", type = "email", enabled = false),
                ChannelConfig(
                    id = "discord",
                    type = "webhook",
                    enabled = false,
                    settingsJson = mapOf("preset" to "discord"),
                ),
                ChannelConfig(id = "pushover", type = "pushover", enabled = false),
            ),
        ),
    )

    private fun setContent(harness: Harness) {
        compose.setContent {
            SettingsScreen(viewModel = harness.viewModel)
        }
        compose.waitUntil(5000) { harness.viewModel.draft.value != null }
        compose.waitForIdle()
    }

    private fun expandSection(title: String) {
        compose.onNodeWithTag(sectionTag(title)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun expandChannel(id: String) {
        compose.onNodeWithTag("channelHeader_$id").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * Face-recognition UI exists only in the full flavor (the fdroid flavor
     * ships no embedding weights and hides the whole section). Full-only
     * tests call this first so the fdroid variant skips them instead of
     * failing on absent nodes.
     */
    private fun requireFaceRecognition() {
        Assume.assumeTrue(
            "face recognition UI is hidden in the fdroid flavor",
            BuildConfig.FACE_RECOGNITION_SUPPORTED,
        )
    }

    /**
     * Waits for a confirm dialog (dialog windows lag a frame behind in
     * Robolectric — never assert on one straight after the click), asserts
     * its text, and confirms it.
     */
    private fun confirmClearDialog(dialogText: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(dialogText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(dialogText).assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
    }

    @Test
    fun sectionsCollapsedByDefaultHideNestedFields() {
        setContent(Harness())

        // Channel fields are inside collapsed sections/channels.
        compose.onAllNodesWithTag(fieldTag("telegram", "Bot token")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onAllNodesWithTag(fieldTag("email", "SMTP host")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        // Section headers themselves exist.
        compose.onNodeWithTag(sectionTag("Notification Channels")).assertExists()
        compose.onNodeWithTag(sectionTag("Detectors")).assertExists()
        compose.onNodeWithTag(sectionTag("Video clips")).assertExists()
    }

    @Test
    fun expandingSectionRevealsChannelCardsAndExpandingCardRevealsFields() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        // Channel cards now visible (header rows).
        compose.onNodeWithTag("channelHeader_telegram").assertExists()

        expandChannel("telegram")
        compose.onNodeWithTag(fieldTag("telegram", "Bot token")).performScrollTo().assertIsDisplayed()

        // Other channels still collapsed.
        compose.onAllNodesWithTag(fieldTag("email", "SMTP host")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun secretFieldVisibilityToggleRevealsPassword() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        expandChannel("email")
        compose.onNodeWithTag(fieldTag("email", "Password / app password")).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Show Password / app password").performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide Password / app password").assertIsDisplayed()
    }

    @Test
    fun emailPasswordIsTrimmedWhenBuildingConfigs() {
        val merged = buildChannelConfigs(
            listOf(ChannelConfig(id = "email", type = "email")),
            mapOf("email.password" to "  s3cret  "),
        ).single()
        assertEquals("s3cret", EmailChannelSettings.fromJson(merged.settingsJson).password)
    }

    @Test
    fun emailChannelCardRendersAboveTelegram() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        val emailY = compose.onNodeWithTag("channelHeader_email")
            .fetchSemanticsNode().positionInRoot.y
        val telegramY = compose.onNodeWithTag("channelHeader_telegram")
            .fetchSemanticsNode().positionInRoot.y
        assertTrue("email card should render above telegram", emailY < telegramY)
    }

    @Test
    fun addChannelCreatesSecondEmailAccount() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        compose.onNodeWithTag("addChannel").performScrollTo().performClick()
        compose.onNodeWithText("Email account").performClick()
        compose.waitForIdle()

        expandChannel("email-2")
        compose.onNodeWithTag(fieldTag("email-2", "SMTP host")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("channelHeader_email-2").performScrollTo()
        compose.onNodeWithText("Email 2").assertIsDisplayed()
    }

    @Test
    fun accountLabelReplacesDerivedName() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        expandChannel("email")
        compose.onNodeWithTag("channelLabel_email").performScrollTo()
            .performTextInput("Work")
        compose.waitForIdle()
        compose.onNodeWithTag("channelHeader_email").performScrollTo()
            .assertTextContains("Work")
    }

    @Test
    fun deleteChannelRemovesCardAfterConfirm() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        compose.onNodeWithTag("addChannel").performScrollTo().performClick()
        compose.onNodeWithText("Email account").performClick()
        compose.waitForIdle()
        expandChannel("email-2")

        compose.onNodeWithContentDescription("Delete Email 2").performScrollTo()
            .performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Delete Email 2?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Delete Email 2?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("channelCard_email-2").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun emptyDefaultsShowAddHintAndNoChannelCards() {
        setContent(Harness())

        expandSection("Notification Channels")
        compose.onNodeWithText("No notification channels yet — add one below.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("addChannel").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("channelCard_email").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun channelCardsShowTypeIcons() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        expandChannel("email")
        expandChannel("telegram")
        compose.onNodeWithContentDescription("Email").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Telegram").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun staleClearDurationFallsBackInsteadOfCrashing() {
        val harness = Harness()
        setContent(harness)

        expandSection("Events")
        // Default retention is 7 days: pick its derived option (168h).
        compose.onNodeWithTag("clearEventsOlderThan").performScrollTo().performClick()
        compose.onNodeWithText("7 days (retention)").performClick()
        compose.waitForIdle()
        // Shrink retention so the picked duration vanishes from the options.
        compose.runOnIdle {
            harness.viewModel.update { it.copy(retentionDays = 1) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("clearEventsOlderThan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("24 hours").assertIsDisplayed()
    }

    @Test
    fun pushoverShowsEmergencyFields() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        expandChannel("pushover")
        for (label in listOf("Priority", "Emergency retry seconds", "Emergency expiry seconds")) {
            compose.onNodeWithTag(fieldTag("pushover", label)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun rendersEmailWebhookAndPushoverChannelFields() {
        val harness = channelsHarness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("telegram")
        expandChannel("email")
        expandChannel("discord")
        expandChannel("pushover")

        for ((id, label) in listOf(
            "telegram" to "Bot token",
            "telegram" to "Chat ID",
            "email" to "SMTP host",
            "email" to "Port (587 or 465)",
            "email" to "Username",
            "email" to "Password / app password",
            "email" to "From address",
            "email" to "To address",
            "discord" to "Webhook URL",
            "pushover" to "App token",
            "pushover" to "User key",
        )) {
            compose.onNodeWithTag(fieldTag(id, label)).performScrollTo().assertIsDisplayed()
        }
        // log is internal plumbing, not a user-toggleable channel.
        compose.onNodeWithTag(switchTag("log")).assertDoesNotExist()
    }

    @Test
    fun savePersistsEmailWebhookAndPresetSelection() {
        val harness = channelsHarness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("email")
        expandChannel("discord")

        compose.onNodeWithTag(fieldTag("email", "SMTP host")).performScrollTo()
            .performTextInput("smtp.example.com")
        compose.onNodeWithTag(fieldTag("email", "Port (587 or 465)")).performScrollTo()
            .performTextReplacement("587")
        compose.onNodeWithTag(fieldTag("email", "To address")).performScrollTo()
            .performTextInput("alice@example.com")

        compose.onNodeWithTag("webhookPreset_discord").performScrollTo().performClick()
        compose.onNodeWithText("slack").performClick()

        compose.onNodeWithTag(fieldTag("discord", "Webhook URL")).performScrollTo()
            .performTextInput("https://discord.com/api/webhooks/1/abc")

        compose.onNodeWithTag("saveSettings").performClick()
        compose.waitUntil(5000) { harness.saved.isNotEmpty() }

        val settings = harness.saved.single()
        assertEquals(
            listOf("log", "telegram", "email", "discord", "pushover"),
            settings.channelConfigs.map { it.id },
        )
        val email = EmailChannelSettings.fromJson(
            settings.channelConfigs.first { it.type == "email" }.settingsJson,
        )
        assertEquals("smtp.example.com", email.host)
        assertEquals(587, email.port)
        assertEquals("alice@example.com", email.to)
        val webhook = WebhookChannelSettings.fromJson(
            settings.channelConfigs.first { it.type == "webhook" }.settingsJson,
        )
        assertEquals("slack", webhook.preset)
        assertEquals("https://discord.com/api/webhooks/1/abc", webhook.url)
        assertTrue(settings.retentionDays >= 0)
    }

    @Test
    fun savePersistsPushoverSettings() {
        val harness = channelsHarness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("pushover")

        compose.onNodeWithTag(fieldTag("pushover", "App token")).performScrollTo()
            .performTextInput("apptok123")
        compose.onNodeWithTag(fieldTag("pushover", "User key")).performScrollTo()
            .performTextInput("userkey456")

        compose.onNodeWithTag("saveSettings").performClick()
        compose.waitUntil(5000) { harness.saved.isNotEmpty() }

        val pushover = PushoverChannelSettings.fromJson(
            harness.saved.single().channelConfigs.first { it.type == "pushover" }.settingsJson,
        )
        assertEquals("apptok123", pushover.appToken)
        assertEquals("userkey456", pushover.userKey)
    }

    @Test
    fun pushoverSoundDropdownDefaultsAndPersistsSelection() {
        val harness = channelsHarness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("pushover")

        compose.onNodeWithTag(fieldTag("pushover", "Sound")).performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Default")

        compose.onNodeWithTag(fieldTag("pushover", "Sound")).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("siren").fetchSemanticsNodes().isNotEmpty()
        }
        // The 24-item menu scrolls: bring the item into the menu viewport
        // before clicking or the tap lands outside and dismisses it.
        compose.onNodeWithText("siren").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("saveSettings").performClick()
        compose.waitUntil(5000) { harness.saved.isNotEmpty() }

        val pushover = PushoverChannelSettings.fromJson(
            harness.saved.single().channelConfigs.first { it.type == "pushover" }.settingsJson,
        )
        assertEquals("siren", pushover.sound)
    }

    @Test
    fun pushoverGarbagePriorityBlocksSendTest() {
        setContent(channelsHarness())

        expandSection("Notification Channels")
        expandChannel("pushover")

        // Valid tokens first so the retry check is the one that fires.
        compose.onNodeWithTag(fieldTag("pushover", "App token")).performScrollTo()
            .performTextInput("apptok")
        compose.onNodeWithTag(fieldTag("pushover", "User key")).performScrollTo()
            .performTextInput("userkey")
        // Priority is now a combo with verbose labels, not a free-form number field.
        compose.onNodeWithTag(fieldTag("pushover", "Priority")).performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Normal (0)", substring = true)
        compose.onNodeWithTag(fieldTag("pushover", "Priority")).performClick()
        compose.onNodeWithText("High (1) — bypass quiet hours").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(fieldTag("pushover", "Priority")).assertTextContains("High (1)", substring = true)

        // Garbage emergency retry still blocks Send test (priority validation itself is now via combo).
        compose.onNodeWithTag(fieldTag("pushover", "Emergency retry seconds")).performScrollTo()
            .performTextReplacement("high")
        compose.waitForIdle()

        compose.onNodeWithText("Emergency retry must be a whole number of seconds")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("sendTest_pushover").assertIsNotEnabled()
    }

    @Test
    fun recordVideoToggleSavesTheVideoClipPreference() {
        val harness = Harness()
        setContent(harness)
        assertTrue(harness.viewModel.draft.value!!.recordVideo)

        expandSection("Video clips")
        compose.onNodeWithTag(switchTag("Record video locally")).performScrollTo().performClick()
        compose.onNodeWithTag("saveSettings").performClick()
        compose.waitUntil(5000) { harness.saved.isNotEmpty() }

        assertFalse(harness.saved.single().recordVideo)
    }

    @Test
    fun qualityDropdownSavesTheRecordingQuality() {
        val harness = Harness()
        setContent(harness)
        assertEquals("lowest", harness.viewModel.draft.value!!.videoQuality)

        expandSection("Video clips")
        compose.onNodeWithTag("videoQualityDropdown").performScrollTo().performClick()
        compose.onNodeWithText("Full HD (1080p)").performClick()

        compose.onNodeWithTag("saveSettings").performClick()
        compose.waitUntil(5000) { harness.saved.isNotEmpty() }

        assertEquals("fhd", harness.saved.single().videoQuality)
    }

    @Test
    fun rollSlidersAndQualityDisableWhenRecordingOff() {
        val harness = Harness()
        setContent(harness)

        expandSection("Video clips")
        compose.onNodeWithTag(switchTag("Record video locally")).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("videoQualityDropdown").performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithTag("preRollSlider").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("postRollSlider").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun livestockDetectorFoldDownShowsSpeciesHint() {
        // The fold-down renders only while the detector is enabled.
        val defaults = AppSettings.defaults()
        val livestockOn = defaults.detectorConfigs.mapValues { (type, cfg) ->
            if (type == io.securitycam.level2.core.TriggerType.livestock) {
                cfg.copy(enabled = true)
            } else {
                cfg
            }
        }
        setContent(Harness(defaults.copyWith(detectorConfigs = livestockOn)))

        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_livestock").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Detects cows, sheep and horses.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detectorsSectionExplainsThresholdSensitivity() {
        setContent(Harness())
        expandSection("Detectors")

        compose.onNodeWithText("higher catches more", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detectorCardShowsSensitivityScale() {
        setContent(Harness())
        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_person").performScrollTo().performClick()
        compose.waitForIdle()

        // Person default threshold 0.5 → sensitivity 11/20 (Medium).
        compose.onNodeWithText("Sensitivity: 11/20 (Medium)", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun advancedSectionOffersDetectionSpeed() {
        setContent(Harness())
        expandSection("Advanced")

        compose.onNodeWithTag("detectionSpeedDropdown")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Best accuracy", substring = false)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detectorsSectionHidesMultiDetectorHintByDefault() {
        // Defaults enable no YOLO detectors (motion/health only).
        setContent(Harness())
        expandSection("Detectors")

        compose.onAllNodesWithText("older phones", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun detectorsSectionShowsHintWithTwoVisionDetectors() {
        val base = AppSettings.defaults()
        val configs = base.detectorConfigs.toMutableMap()
        configs[TriggerType.person] = DetectorConfig(type = TriggerType.person, enabled = true)
        configs[TriggerType.vehicle] = DetectorConfig(type = TriggerType.vehicle, enabled = true)
        setContent(Harness(base.copyWith(detectorConfigs = configs)))
        expandSection("Detectors")

        compose.onNodeWithText("older phones", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun disabledDetectorCardStillUnfolds() {
        // Dog ships disabled; the fold-down must open anyway so it can be
        // pre-configured before enabling.
        setContent(Harness())
        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_dog").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Triggers on sight or sound (barking, growling).")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detectorSectionShowsCameraAudioCombinedSystemGroups() {
        setContent(Harness())
        expandSection("Detectors")

        for (heading in listOf("Camera", "Audio", "Combined")) {
            compose.onNodeWithTag("detectorGroup_$heading").performScrollTo().assertIsDisplayed()
        }
        // Combined pet cards present under their group.
        compose.onNodeWithTag("detectorHeader_dog").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detectorHeader_cat").performScrollTo().assertIsDisplayed()
        // Heartbeat lives under Advanced now, not Detectors — as a plain
        // switch row, not a detector card.
        compose.onAllNodesWithTag("detectorHeader_health").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        expandSection("Advanced")
        compose.onNodeWithTag("detectorHeader_heart").assertDoesNotExist()
        compose.onNodeWithTag("detectorHeader_health").assertDoesNotExist()
        compose.onNodeWithTag("heartbeatSwitch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Heartbeat", substring = false).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun advancedSectionOrdersMergeWindowAndAlertLog() {
        setContent(Harness())
        expandSection("Advanced")

        // Alert-log row carries its new label…
        compose.onNodeWithText("View Alert Log", substring = false)
            .performScrollTo()
            .assertIsDisplayed()
        // …and the merge-window label, slider and explainer read top-down.
        val label = compose.onNodeWithText("Merge window:", substring = true)
            .performScrollTo()
        label.assertIsDisplayed()
        compose.onNodeWithTag("mergeWindowSlider").performScrollTo().assertIsDisplayed()
        val explainer = compose.onNodeWithText("grouped into a single notification", substring = true)
            .performScrollTo()
        explainer.assertIsDisplayed()
        val labelY = label.fetchSemanticsNode().positionInRoot.y
        val sliderY = compose.onNodeWithTag("mergeWindowSlider").fetchSemanticsNode().positionInRoot.y
        val explainerY = explainer.fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "label ($labelY) < slider ($sliderY) < explainer ($explainerY)",
            labelY < sliderY && sliderY < explainerY,
        )
    }

    @Test
    fun dividerSeparatesCameraComboFromDetectors() {
        setContent(Harness())
        compose.onNodeWithTag("cameraDetectorsDivider").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detectorGroupsShowExplainerLines() {
        setContent(Harness())
        expandSection("Detectors")

        compose.onNodeWithText("things the camera sees", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Listen for sounds", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("camera and microphone together", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun versionSitsDirectlyUnderAboutHeading() {
        setContent(Harness())
        compose.onNodeWithTag(sectionTag("About Level 2")).performScrollTo().assertIsDisplayed()

        val version = compose.onNodeWithText("Version ", substring = true).performScrollTo()
        version.assertIsDisplayed()
        val mission = compose.onNodeWithText("feel safe and secure", substring = true)
            .performScrollTo()
        mission.assertIsDisplayed()
        val versionY = version.fetchSemanticsNode().positionInRoot.y
        val missionY = mission.fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "version ($versionY) should sit above the mission text ($missionY)",
            versionY < missionY,
        )
    }

    @Test
    fun fieldTagIsQualifiedByChannelId() {
        assertEquals("field_email_smtp_host", fieldTag("email", "SMTP host"))
        assertEquals("field_email-2_smtp_host", fieldTag("email-2", "SMTP host"))
    }

    @Test
    fun sameTypeChannelCardsHaveDistinctFieldTags() {
        val harness = Harness(
            AppSettings.defaults().copyWith(
                channelConfigs = listOf(
                    ChannelConfig(id = "log", type = "log", enabled = true),
                    ChannelConfig(id = "email", type = "email", enabled = false),
                    ChannelConfig(id = "email-2", type = "email", enabled = false),
                ),
            ),
        )
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("email")
        expandChannel("email-2")
        compose.onNodeWithTag(fieldTag("email", "SMTP host")).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(fieldTag("email-2", "SMTP host")).performScrollTo()
            .assertIsDisplayed()
        // Exactly one node per qualified tag — no collision.
        compose.onAllNodesWithTag(fieldTag("email", "SMTP host")).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
    }

    @Test
    fun detectorCardHasNoPerChannelRouteRows() {
        // Detectors fan out to all active channels; there is no per-detector
        // routing UI anymore.
        val harness = Harness(
            AppSettings.defaults().copyWith(
                channelConfigs = listOf(
                    ChannelConfig(id = "log", type = "log", enabled = true),
                    ChannelConfig(id = "email", type = "email", enabled = false, label = "Work"),
                    ChannelConfig(id = "email-2", type = "email", enabled = false),
                ),
            ),
        )
        setContent(harness)

        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_motion").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag("detectorRoute_motion_email", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithTag("detectorRoute_motion_email-2", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("detectorRoute_motion_log").assertDoesNotExist()
    }

    @Test
    fun liveViewPortFieldIsClearable() {
        val harness = Harness()
        setContent(harness)
        compose.runOnIdle {
            harness.viewModel.update { it.copy(liveView = it.liveView.copy(enabled = true)) }
        }
        compose.waitForIdle()

        expandSection("Live View")
        compose.onNodeWithTag("liveViewPort").performScrollTo().performTextReplacement("8555")
        compose.waitForIdle()
        assertEquals(8555, harness.viewModel.draft.value!!.liveView.port)

        // Clearing must empty the field (the old toIntOrNull filter swallowed clears).
        compose.onNodeWithTag("liveViewPort").performTextClearance()
        compose.waitForIdle()
        // assertTextEquals would also match the "Port" label in merged text;
        // read the editable content directly instead.
        val editable = compose.onNodeWithTag("liveViewPort").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals("", editable)
        // The draft keeps the last valid port.
        assertEquals(8555, harness.viewModel.draft.value!!.liveView.port)
    }

    @Test
    fun liveViewAuthTogglePreservesCustomUsername() {
        val harness = Harness()
        setContent(harness)
        compose.runOnIdle {
            harness.viewModel.update {
                it.copy(
                    liveView = it.liveView.copy(
                        enabled = true,
                        username = "cameraman",
                        password = "s3cret",
                    ),
                )
            }
        }
        compose.waitForIdle()

        expandSection("Live View")
        compose.onNodeWithTag(switchTag("Require authentication")).performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals("", harness.viewModel.draft.value!!.liveView.username)
        compose.onNodeWithTag(switchTag("Require authentication")).performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals("cameraman", harness.viewModel.draft.value!!.liveView.username)
    }

    @Test
    fun liveViewAuthDefaultsToAdminWhenBlank() {
        val harness = Harness()
        setContent(harness)
        compose.runOnIdle {
            harness.viewModel.update { it.copy(liveView = it.liveView.copy(enabled = true)) }
        }
        compose.waitForIdle()
        assertEquals("", harness.viewModel.draft.value!!.liveView.username)

        expandSection("Live View")
        compose.onNodeWithTag(switchTag("Require authentication")).performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals("admin", harness.viewModel.draft.value!!.liveView.username)
    }

    @Test
    fun clearEventsDialogSurvivesRotation() {
        val dialogText = "Delete ALL recorded events and their snapshots and videos?"
        val harness = Harness()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SettingsScreen(viewModel = harness.viewModel)
        }
        compose.waitUntil(5000) { harness.viewModel.draft.value != null }
        compose.waitForIdle()

        expandSection("Events")
        compose.onNodeWithText("Clear all events").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(dialogText).fetchSemanticsNodes().isNotEmpty()
        }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(dialogText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(dialogText).assertIsDisplayed()
    }

    // ---- Face photo gallery ----

    /** Writes a real 8x8 JPEG so Robolectric decodes gallery thumbs. */
    private fun writeJpeg(file: File) {
        file.parentFile?.mkdirs()
        val bmp = android.graphics.Bitmap.createBitmap(
            8, 8, android.graphics.Bitmap.Config.ARGB_8888,
        )
        file.outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
        }
        bmp.recycle()
    }

    private data class GallerySeed(val harness: Harness, val face: KnownFace)

    /**
     * Harness with recognition enabled, one enrolled face, and [photoCount]
     * real JPEGs on disk (plus journaled embeddings so deletes unlearn).
     */
    private fun gallerySeed(photoCount: Int): GallerySeed {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val face = KnownFace(id = "face_gallery1", label = "Gally")
        val initial = AppSettings.defaults()
            .copyWith(knownFaces = listOf(face))
            .withFaceRecognition(true)
        val harness = Harness(initial = initial, application = app)
        val store = KnownFaceStore(app)
        val vectors = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        repeat(photoCount) { k ->
            store.enroll(face.id, vectors[k % vectors.size])
            store.appendSample(face.id, k, vectors[k % vectors.size])
            writeJpeg(store.photoFileFor(face.id, k))
        }
        return GallerySeed(harness, face)
    }

    private fun openFaceRows(harness: Harness) {
        setContent(harness)
        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_face").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openGallery(face: KnownFace) {
        compose.onNodeWithTag("faceThumbnail_${face.id}").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Photos of ${face.label}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Photos of ${face.label}").assertIsDisplayed()
    }

    @Test
    fun tappingFaceThumbnailOpensGallery() {
        requireFaceRecognition()
        val (harness, face) = gallerySeed(photoCount = 2)
        openFaceRows(harness)
        compose.onNodeWithText("2 photos").performScrollTo().assertIsDisplayed()

        openGallery(face)

        compose.onNodeWithTag("faceGalleryDialog").assertIsDisplayed()
    }

    @Test
    fun galleryWithOnePhotoShowsNoDelete() {
        requireFaceRecognition()
        val (harness, face) = gallerySeed(photoCount = 1)
        openFaceRows(harness)

        openGallery(face)

        compose.onNodeWithTag("galleryPhoto_${face.id}_0").assertIsDisplayed()
        compose.onAllNodesWithTag("deletePhoto_${face.id}_0").assertCountEquals(0)
    }

    @Test
    fun galleryDeleteRemovesPhotoAndUnlearns() {
        requireFaceRecognition()
        val (harness, face) = gallerySeed(photoCount = 2)
        openFaceRows(harness)

        openGallery(face)
        compose.onNodeWithTag("deletePhoto_${face.id}_1").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(
                "It will be deleted and no longer used for recognition.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "It will be deleted and no longer used for recognition.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()

        compose.waitForIdle()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("1 photo").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 photo").assertIsDisplayed()
        compose.onAllNodesWithTag("deletePhoto_${face.id}_1").assertCountEquals(0)

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = KnownFaceStore(app)
        assertFalse(store.photoFileFor(face.id, 1).exists())
        assertEquals(1, store.sampleCount(face.id))
    }

    @Test
    fun galleryPhotoTapOpensZoomAndCloses() {
        requireFaceRecognition()
        val (harness, face) = gallerySeed(photoCount = 1)
        openFaceRows(harness)

        openGallery(face)
        compose.onNodeWithTag("galleryPhoto_${face.id}_0").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("galleryPhotoClose")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("galleryPhotoClose").assertIsDisplayed()
        compose.onNodeWithTag("galleryPhotoClose").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("galleryPhotoClose").assertCountEquals(0)
    }

    @Test
    fun faceEnrollNameSurvivesRotation() {
        requireFaceRecognition()
        val harness = Harness()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SettingsScreen(viewModel = harness.viewModel)
        }
        compose.waitUntil(5000) { harness.viewModel.draft.value != null }
        compose.waitForIdle()

        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_face").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("faceRecognitionSwitch").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("addFaceButton").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("faceNameField").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("faceNameField").performTextInput("Ada")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("faceNameField").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("faceNameField").assertTextContains("Ada")
    }

    @Test
    fun faceRecognitionLivesInsideFaceDetectorCard() {
        requireFaceRecognition()
        val harness = Harness()
        setContent(harness)

        // The old top-level section is gone; the sub-heading lives inside
        // the face detector card body.
        compose.onNodeWithTag(sectionTag("Face Recognition")).assertDoesNotExist()
        expandSection("Detectors")
        compose.onAllNodesWithTag("faceRecognitionSwitch").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onNodeWithTag("detectorHeader_face").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Face Recognition").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("faceRecognitionSwitch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun faceRecognitionHiddenWhenUnsupported() {
        Assume.assumeFalse(
            "full flavor shows the face recognition switch",
            BuildConfig.FACE_RECOGNITION_SUPPORTED,
        )
        val harness = Harness()
        setContent(harness)

        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_face").performScrollTo().performClick()
        compose.waitForIdle()
        // No switch, no enrolled list, no Add face: plain face detection
        // stays, recognition UI is gone entirely.
        compose.onAllNodesWithTag("faceRecognitionSwitch").assertCountEquals(0)
        compose.onAllNodesWithTag("addFaceButton").assertCountEquals(0)
        compose.onAllNodesWithText("Face Recognition").assertCountEquals(0)
    }

    @Test
    fun disablingFaceDetectorForcesRecognitionOff() {
        requireFaceRecognition()
        val harness = Harness()
        setContent(harness)

        expandSection("Detectors")
        compose.onNodeWithTag("detectorHeader_face").performScrollTo().performClick()
        compose.waitForIdle()
        // Enable face detection + recognition.
        compose.onNodeWithTag("detectorEnabled_face").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("faceRecognitionSwitch").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(AppSettings.faceRecognitionEnabled(harness.viewModel.draft.value!!))

        // Switch the face detector off: recognition must follow.
        compose.onNodeWithTag("detectorEnabled_face").performScrollTo().performClick()
        compose.waitForIdle()
        val draft = harness.viewModel.draft.value!!
        assertFalse(AppSettings.faceRecognitionEnabled(draft))
        assertFalse(draft.detectorConfigs.getValue(TriggerType.face).enabled)
    }

    @Test
    fun clearEventsDefaultsTo24HoursWithDayLabel() {
        val harness = Harness()
        setContent(harness)
        expandSection("Events")

        compose.onNodeWithTag("clearEventsOlderThan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clear events").performScrollTo().performClick()
        confirmClearDialog("Delete events older than 1d and their snapshots and videos?")
        compose.waitUntil(5000) { harness.cleared.isNotEmpty() }

        assertEquals(listOf(Duration.ofHours(24)), harness.cleared)
    }

    @Test
    fun clearEventsDurationDropdownSelects48Hours() {
        val harness = Harness()
        setContent(harness)
        expandSection("Events")

        compose.onNodeWithTag("clearEventsOlderThan").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("48 hours").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Clear events").performScrollTo().performClick()
        confirmClearDialog("Delete events older than 2d and their snapshots and videos?")
        compose.waitUntil(5000) { harness.cleared.isNotEmpty() }

        assertEquals(listOf(Duration.ofHours(48)), harness.cleared)
    }

    @Test
    fun clearEventsRetentionOptionClears168Hours() {
        val harness = Harness()
        setContent(harness)
        expandSection("Events")

        compose.onNodeWithTag("clearEventsOlderThan").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("7 days (retention)").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Clear events").performScrollTo().performClick()
        confirmClearDialog("Delete events older than 7d and their snapshots and videos?")
        compose.waitUntil(5000) { harness.cleared.isNotEmpty() }
        assertEquals(listOf(Duration.ofHours(168)), harness.cleared)
    }

    @Test
    fun clearAllEventsClearsEverything() {
        val harness = Harness()
        setContent(harness)
        expandSection("Events")

        compose.onNodeWithText("Clear all events").performScrollTo().performClick()
        confirmClearDialog("Delete ALL recorded events and their snapshots and videos?")
        compose.waitUntil(5000) { harness.cleared.isNotEmpty() }
        assertEquals(listOf(null), harness.cleared)
    }

    @Test
    fun advancedSectionShowsPreviewSliders() {
        setContent(Harness())
        expandSection("Advanced")
        compose.onNodeWithTag("previewFpsSlider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("previewWidthSlider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preview frame rate:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preview width:", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun channelFrequencyDropdownDefaultsToEveryTriggerAndSelectsPerWave() {
        val harness = channelsHarness()
        setContent(harness)
        expandSection("Notification Channels")
        expandChannel("telegram")
        compose.onNodeWithTag("channelFrequency_telegram").performScrollTo().assertIsDisplayed()
        val before = harness.viewModel.draft.value!!.channelConfigs.first { it.id == "telegram" }
        assertEquals("every_trigger", before.alertMode)
        compose.onNodeWithTag("channelFrequency_telegram").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Once per wave").performClick()
        compose.waitForIdle()
        val after = harness.viewModel.draft.value!!.channelConfigs.first { it.id == "telegram" }
        assertEquals("per_wave", after.alertMode)
    }

    @Test
    fun channelPreviewToggleVisibleOnAllChannelTypesAndFlips() {
        val harness = channelsHarness()
        setContent(harness)
        expandSection("Notification Channels")
        // Every non-log channel should expose the preview toggle.
        for (id in listOf("telegram", "email", "discord", "pushover")) {
            expandChannel(id)
            compose.onNodeWithTag("channelPreview_$id").performScrollTo().assertIsDisplayed()
        }
        assertFalse(harness.viewModel.draft.value!!.channelConfigs.first { it.id == "telegram" }.pushVideoPreview)
        compose.onNodeWithTag("channelPreview_telegram").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(harness.viewModel.draft.value!!.channelConfigs.first { it.id == "telegram" }.pushVideoPreview)
    }
}
