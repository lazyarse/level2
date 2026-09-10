package io.securitycam.level2.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        compose.onAllNodesWithTag(fieldTag("Bot token")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        compose.onAllNodesWithTag(fieldTag("SMTP host")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        // Section headers themselves exist.
        compose.onNodeWithTag(sectionTag("Notification Channels")).assertExists()
        compose.onNodeWithTag(sectionTag("Detectors")).assertExists()
        compose.onNodeWithTag(sectionTag("Video clips")).assertExists()
    }

    @Test
    fun expandingSectionRevealsChannelCardsAndExpandingCardRevealsFields() {
        setContent(Harness())

        expandSection("Notification Channels")
        // Channel cards now visible (header rows).
        compose.onNodeWithTag("channelHeader_telegram").assertExists()

        expandChannel("telegram")
        compose.onNodeWithTag(fieldTag("Bot token")).performScrollTo().assertIsDisplayed()

        // Other channels still collapsed.
        compose.onAllNodesWithTag(fieldTag("SMTP host")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    @Test
    fun secretFieldVisibilityToggleRevealsPassword() {
        setContent(Harness())

        expandSection("Notification Channels")
        expandChannel("email")
        compose.onNodeWithTag(fieldTag("Password / app password")).performScrollTo()
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
    fun rendersEmailWebhookAndPushoverChannelFields() {
        val harness = Harness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("telegram")
        expandChannel("email")
        expandChannel("discord")
        expandChannel("pushover")

        for (label in listOf(
            "Bot token",
            "Chat ID",
            "SMTP host",
            "Port (587 or 465)",
            "Username",
            "Password / app password",
            "From address",
            "To address",
            "Webhook URL",
            "App token",
            "User key",
        )) {
            compose.onNodeWithTag(fieldTag(label)).performScrollTo().assertIsDisplayed()
        }
        // log is internal plumbing, not a user-toggleable channel.
        compose.onNodeWithTag(switchTag("log")).assertDoesNotExist()
    }

    @Test
    fun savePersistsEmailWebhookAndPresetSelection() {
        val harness = Harness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("email")
        expandChannel("discord")

        compose.onNodeWithTag(fieldTag("SMTP host")).performScrollTo()
            .performTextInput("smtp.example.com")
        compose.onNodeWithTag(fieldTag("Port (587 or 465)")).performScrollTo()
            .performTextReplacement("587")
        compose.onNodeWithTag(fieldTag("To address")).performScrollTo()
            .performTextInput("alice@example.com")

        compose.onNodeWithTag("webhookPreset_discord").performScrollTo().performClick()
        compose.onNodeWithText("slack").performClick()

        compose.onNodeWithTag(fieldTag("Webhook URL")).performScrollTo()
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
        val harness = Harness()
        setContent(harness)

        expandSection("Notification Channels")
        expandChannel("pushover")

        compose.onNodeWithTag(fieldTag("App token")).performScrollTo()
            .performTextInput("apptok123")
        compose.onNodeWithTag(fieldTag("User key")).performScrollTo()
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
}
