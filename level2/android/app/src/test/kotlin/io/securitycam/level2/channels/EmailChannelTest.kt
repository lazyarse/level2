package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `test/email_channel_test.dart`. */
class EmailChannelTest {

    private fun channel(sender: MailSender?): EmailChannel = EmailChannel(
        id = "email",
        enabled = true,
        settings = EmailChannelSettings(
            host = "smtp.example.com",
            port = 587,
            username = "alice",
            password = "secret",
            from = "alice@example.com",
            to = "bob@example.com",
        ),
        sender = sender,
    )

    @Test
    fun sendDeliversTheAlertTextAsTheMessageBody() = runBlocking {
        val sent = mutableListOf<MailMessage>()
        val c = channel { m -> sent.add(m) }

        c.send(
            AlertMessage(
                timestamp = Instant.EPOCH,
                triggerType = "motion",
                text = "Motion detected in Hallway",
            ),
        )

        assertEquals(1, sent.size)
        assertEquals("alice@example.com", sent.single().from)
        assertEquals("bob@example.com", sent.single().to)
        assertEquals("Motion detected in Hallway", sent.single().subject)
        assertEquals("Motion detected in Hallway", sent.single().text)
    }

    @Test
    fun sendTestDeliversATestMessage() = runBlocking {
        val sent = mutableListOf<MailMessage>()
        val c = channel { m -> sent.add(m) }

        c.sendTest()

        assertEquals("Security Cam: test alert", sent.single().subject)
    }

    @Test
    fun validateRequiresHostCredentialsAndValidAddresses() {
        assertEquals("SMTP host is required", EmailChannel(id = "email", settings = EmailChannelSettings()).validate())
        assertNull(channel { }.validate())
        assertEquals(
            "From address is invalid",
            EmailChannel(
                id = "email",
                settings = EmailChannelSettings(
                    host = "smtp.example.com",
                    username = "alice",
                    password = "secret",
                    from = "not-an-email",
                    to = "bob@example.com",
                ),
            ).validate(),
        )
    }

    @Test
    fun secretFieldsHidesThePassword() {
        assertTrue(EmailChannelSettings(password = "x").secretFields.contains("password"))
    }
}