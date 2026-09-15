package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.Snapshot
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Synthetic stand-in: the real generator needs Bitmap (Robolectric).
        testSnapshot = { Snapshot(byteArrayOf(1), "image/jpeg", "test-snapshot.jpg") },
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
    fun sendTestAttachesSampleSnapshot() = runBlocking {
        val sent = mutableListOf<MailMessage>()
        val snap = Snapshot(byteArrayOf(1, 2, 3), "image/jpeg", "test-snapshot.jpg")
        val c = EmailChannel(
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
            sender = MailSender { m -> sent.add(m) },
            testSnapshot = { snap },
        )

        c.sendTest()

        assertEquals("Security Cam: test alert", sent.single().subject)
        assertEquals(snap, sent.single().attachment)
    }

    @Test
    fun sampleSnapshotRendersAsMultipart() {
        val snap = Snapshot(byteArrayOf(4, 5, 6), "image/jpeg", "test-snapshot.jpg")
        val rendered = sender().renderMessage(
            MailMessage(
                from = "a@b.c",
                to = "d@e.f",
                subject = "Security Cam: test alert",
                text = "Security Cam: test alert",
                attachment = snap,
            ),
        )
        assertTrue(rendered.contains("multipart/mixed"))
        assertTrue(rendered.contains("filename=\"test-snapshot.jpg\""))
        assertTrue(rendered.endsWith("\r\n.\r\n"))
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

    @Test
    fun previewUrlParsesEtherealDataReply() {
        assertEquals(
            "https://ethereal.email/message/WaPu7QsssRQCysQBWaPu7nSCFzXO",
            RawSmtpSender.previewUrlFromDataReply(
                "250 Ok: queued as [STATUS=SUCCESS MSGID=WaPu7QsssRQCysQBWaPu7nSCFzXO]",
            ),
        )
    }

    @Test
    fun previewUrlIsNullWithoutMsgid() {
        assertNull(RawSmtpSender.previewUrlFromDataReply("250 2.0.0 OK message queued"))
        assertNull(RawSmtpSender.previewUrlFromDataReply(""))
    }

    @Test
    fun lastPreviewUrlIsNullWithInjectedSender() = runBlocking {
        val c = channel { }
        c.sendTest()
        assertNull(c.lastPreviewUrl)
    }

    private fun channelFor(port: Int, useTls: Boolean): EmailChannel = EmailChannel(
        id = "email",
        settings = EmailChannelSettings(
            host = "smtp.example.com",
            port = port,
            username = "alice",
            password = "secret",
            from = "alice@example.com",
            to = "bob@example.com",
            useTls = useTls,
        ),
    )

    @Test
    fun validateRejectsKnownBadPortModePairings() {
        assertEquals(
            "Port 587 uses STARTTLS — turn Implicit TLS off",
            channelFor(port = 587, useTls = true).validate(),
        )
        assertEquals(
            "Port 465 uses implicit TLS — turn Implicit TLS on",
            channelFor(port = 465, useTls = false).validate(),
        )
    }

    @Test
    fun validateAllowsCorrectPairingsAndCustomPorts() {
        assertNull(channelFor(port = 587, useTls = false).validate())
        assertNull(channelFor(port = 465, useTls = true).validate())
        assertNull(channelFor(port = 2525, useTls = false).validate())
        assertNull(channelFor(port = 2525, useTls = true).validate())
    }

    @Test
    fun tlsFailureHintNamesTheFix() {
        val implicit = RawSmtpSender.tlsFailureHint(
            EmailChannelSettings(host = "smtp.example.com", port = 587, useTls = true),
        )
        assertTrue(implicit.contains("587") && implicit.contains("Implicit TLS off"))
        val starttls = RawSmtpSender.tlsFailureHint(
            EmailChannelSettings(host = "smtp.example.com", port = 465, useTls = false),
        )
        assertTrue(starttls.contains("465") && starttls.contains("Implicit TLS on"))
    }

    @Test
    fun smtpErrorHintExplainsAuthRejection() {
        val hint = RawSmtpSender.smtpErrorHint("AUTH password", 535)
        assertTrue(hint.contains("535"))
        assertTrue(hint.contains("AUTH password"))
        assertTrue(hint.contains("username and password"))
    }

    @Test
    fun smtpErrorHintFallsBackToStepAndCode() {
        assertEquals(
            "SMTP DATA acceptance failed (452)",
            RawSmtpSender.smtpErrorHint("DATA acceptance", 452),
        )
    }

    @Test
    fun smtpErrorHintNeverEchoesCommandText() {
        // AUTH lines carry base64 credentials: the hint must not contain them.
        val blob = java.util.Base64.getEncoder().encodeToString("s3cret".toByteArray())
        val hint = RawSmtpSender.smtpErrorHint("AUTH password", 535)
        assertFalse(hint.contains(blob))
    }

    private fun sender(): RawSmtpSender = RawSmtpSender(
        EmailChannelSettings(
            host = "smtp.example.com",
            username = "alice",
            password = "secret",
            from = "alice@example.com",
            to = "bob@example.com",
        ),
    )

    @Test
    fun renderedMessageEndsWithCrlfDotCrlf() {
        val rendered = sender().renderMessage(
            MailMessage(from = "a@b.c", to = "d@e.f", subject = "s", text = "hello"),
        )
        // Without the trailing CRLF the server waits for end-of-DATA forever
        // and the read times out.
        assertTrue(rendered.endsWith("\r\n.\r\n"))
    }

    @Test
    fun renderedMessageDotStuffsBodyButNotTerminator() {
        val rendered = sender().renderMessage(
            MailMessage(from = "a@b.c", to = "d@e.f", subject = "s", text = ".oops"),
        )
        assertTrue(rendered.contains("\r\n..oops\r\n.\r\n"))
    }

    @Test
    fun renderedMessageCarriesDateAndUniqueMessageId() {
        val first = sender().renderMessage(
            MailMessage(from = "a@b.c", to = "d@e.f", subject = "s", text = "hello"),
        )
        val second = sender().renderMessage(
            MailMessage(from = "a@b.c", to = "d@e.f", subject = "s", text = "hello"),
        )
        assertTrue(first.contains("Date: "))
        val idPattern = Regex("Message-ID: <([^>]+)>")
        val firstId = idPattern.find(first)!!.groupValues[1]
        val secondId = idPattern.find(second)!!.groupValues[1]
        assertTrue(firstId.isNotEmpty())
        assertTrue(secondId.isNotEmpty())
        assertTrue(firstId != secondId)
    }

    @Test
    fun sendForwardsSnapshotAsAttachment() = runBlocking {
        val sent = mutableListOf<MailMessage>()
        val c = channel { m -> sent.add(m) }
        val snap = Snapshot(byteArrayOf(9, 8, 7), "image/jpeg", "snap.jpg")

        c.send(
            AlertMessage(
                timestamp = Instant.EPOCH,
                triggerType = "motion",
                text = "Motion detected in Hallway",
                snapshot = snap,
            ),
        )

        assertEquals(snap, sent.single().attachment)
    }

    @Test
    fun sendWithoutSnapshotStaysTextOnly() = runBlocking {
        val sent = mutableListOf<MailMessage>()
        val c = channel { m -> sent.add(m) }

        c.send(
            AlertMessage(timestamp = Instant.EPOCH, triggerType = "motion", text = "hi"),
        )

        assertNull(sent.single().attachment)
        val rendered = sender().renderMessage(sent.single())
        assertFalse(rendered.contains("multipart"))
    }

    @Test
    fun renderedMessageAttachesSnapshotAsMultipart() {
        val bytes = ByteArray(256) { it.toByte() }
        val rendered = sender().renderMessage(
            MailMessage(
                from = "a@b.c",
                to = "d@e.f",
                subject = "s",
                text = "hello",
                attachment = Snapshot(bytes, "image/jpeg", "snap.jpg"),
            ),
        )

        assertTrue(rendered.contains("multipart/mixed"))
        val boundary = Regex("boundary=\"([^\"]+)\"").find(rendered)!!.groupValues[1]
        assertTrue(rendered.contains("--$boundary\r\n"))
        assertTrue(rendered.contains("--$boundary--\r\n."))
        assertTrue(rendered.contains("Content-Type: image/jpeg"))
        assertTrue(rendered.contains("filename=\"snap.jpg\""))
        val encoded = rendered
            .substringAfter("filename=\"snap.jpg\"\r\n\r\n")
            .substringBefore("\r\n--$boundary--")
        org.junit.Assert.assertArrayEquals(
            bytes,
            java.util.Base64.getMimeDecoder().decode(encoded),
        )
        assertTrue(rendered.endsWith("\r\n.\r\n"))
    }

    @Test
    fun sendForwardsVideoPreviewAsAttachmentWhenNoSnapshot() = runBlocking {
        val sent = mutableListOf<MailMessage>()
        val c = channel { m -> sent.add(m) }
        val preview = Snapshot(byteArrayOf(9, 8, 7), "image/gif", "preview.gif")

        c.send(
            AlertMessage(
                timestamp = Instant.EPOCH,
                triggerType = "motion",
                text = "Motion with preview",
                videoPreview = preview,
            ),
        )

        assertEquals(preview, sent.single().attachment)
        // When both are present, snapshot wins (preview is fallback).
        val sent2 = mutableListOf<MailMessage>()
        val c2 = channel { m -> sent2.add(m) }
        val snap = Snapshot(byteArrayOf(1), "image/jpeg", "snap.jpg")
        c2.send(
            AlertMessage(
                timestamp = Instant.EPOCH,
                triggerType = "motion",
                text = "hi",
                snapshot = snap,
                videoPreview = preview,
            ),
        )
        assertEquals(snap, sent2.single().attachment)
    }
}
