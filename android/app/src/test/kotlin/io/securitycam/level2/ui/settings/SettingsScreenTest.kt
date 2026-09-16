package io.securitycam.level2.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
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
import io.securitycam.level2.channels.EmailChannelSettings
import io.securitycam.level2.channels.PushoverChannelSettings
import io.securitycam.level2.channels.WebhookChannelSettings
import io.securitycam.level2.core.AppSettings
import io.securitycam.level2.core.ChannelConfig
import java.time.Duration
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private class Harness(initial: AppSettings = AppSettings.defaults()) {
        val saved = mutableListOf<AppSettings>()
        val cleared = mutableListOf<Duration?>()
        val viewModel = SettingsViewModel(
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
        for (label in listOf("Priority (-2 to 2)", "Emergency retry seconds", "Emergency expiry seconds")) {
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

        // Valid tokens first so the priority check is the one that fires.
        compose.onNodeWithTag(fieldTag("pushover", "App token")).performScrollTo()
            .performTextInput("apptok")
        compose.onNodeWithTag(fieldTag("pushover", "User key")).performScrollTo()
            .performTextInput("userkey")
        compose.onNodeWithTag(fieldTag("pushover", "Priority (-2 to 2)")).performScrollTo()
            .performTextReplacement("high")
        compose.waitForIdle()

        // The replacement text landed in the field.
        compose.onNodeWithTag(fieldTag("pushover", "Priority (-2 to 2)"))
            .assertTextContains("high", substring = true)

        compose.onNodeWithText("Priority must be a whole number from -2 to 2")
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
        // Heartbeat lives under Advanced now, not Detectors.
        compose.onAllNodesWithTag("detectorHeader_health").fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        expandSection("Advanced")
        compose.onNodeWithTag("detectorHeader_heart").assertDoesNotExist()
        compose.onNodeWithTag("detectorHeader_health").performScrollTo().assertIsDisplayed()
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
    fun detectorRouteRowsShowDisplayNames() {
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

        // Custom label and derived "Email 2" — never raw ids.
        compose.onNodeWithTag("detectorRoute_motion_email").performScrollTo()
            .assertTextContains("Work")
        compose.onNodeWithTag("detectorRoute_motion_email-2").performScrollTo()
            .assertTextContains("Email 2")
        compose.onAllNodesWithText("email-2", substring = false).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        // The alert-log feed is not a per-detector route option anymore.
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

    @Test
    fun faceEnrollNameSurvivesRotation() {
        val harness = Harness()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SettingsScreen(viewModel = harness.viewModel)
        }
        compose.waitUntil(5000) { harness.viewModel.draft.value != null }
        compose.waitForIdle()

        expandSection("Face Recognition")
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
    fun advancedSectionShowsGifPreviewSliders() {
        setContent(Harness())
        expandSection("Advanced")
        compose.onNodeWithTag("gifFpsSlider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("gifWidthSlider").performScrollTo().assertIsDisplayed()
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
