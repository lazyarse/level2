package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings
import io.securitycam.level2.core.Snapshot
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmailChannelSettings(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val password: String = "",
    val from: String = "",
    val to: String = "",
    val useTls: Boolean = false,
) : ChannelSettings() {
    override val type: String get() = "email"
    override fun toJson(): Map<String, Any?> = mapOf(
        "host" to host,
        "port" to port,
        "username" to username,
        "password" to password,
        "from" to from,
        "to" to to,
        "useTls" to useTls,
    )
    override val secretFields: List<String> get() = listOf("password")

    companion object {
        fun fromJson(json: Map<String, Any?>): EmailChannelSettings = EmailChannelSettings(
            host = json["host"] as? String ?: "",
            port = (json["port"] as? Number)?.toInt() ?: 587,
            username = json["username"] as? String ?: "",
            password = json["password"] as? String ?: "",
            from = json["from"] as? String ?: "",
            to = json["to"] as? String ?: "",
            useTls = json["useTls"] as? Boolean ?: false,
        )
    }
}

/** Transport-agnostic mail message (mirrors the fields the Dart tests assert). */
data class MailMessage(
    val from: String,
    val to: String,
    val subject: String,
    val text: String,
    /** Attached snapshot (full camera JPEG); null sends a text-only message. */
    val attachment: Snapshot? = null,
)

fun interface MailSender {
    suspend fun send(message: MailMessage)
}

/**
 * Sample JPEG attached to email test sends so the attachment path is
 * exercisable without waiting for a live detection. Rendered in code
 * (no bundled asset): small labelled frame, ~10KB.
 */
internal fun sampleTestSnapshot(): Snapshot {
    val width = 320
    val height = 240
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
        }
        canvas.drawText("Security Cam test", 24f, height / 2f, paint)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Snapshot(out.toByteArray(), "image/jpeg", "test-snapshot.jpg")
    } finally {
        bitmap.recycle()
    }
}

/**
 * Sends alert emails over SMTP. The real transport is replaceable via the
 * injectable [MailSender] in tests (no live SMTP), mirroring the Dart channel.
 */
class EmailChannel(
    override val id: String,
    override val enabled: Boolean = true,
    override val settings: EmailChannelSettings,
    private val sender: MailSender? = null,
    /** Builds the attachment for test sends; injectable so JVM tests avoid Bitmap. */
    private val testSnapshot: () -> Snapshot = ::sampleTestSnapshot,
) : io.securitycam.level2.core.Channel {

    override val type: String get() = "email"

    /** Live transport, reused so the last preview URL survives the send. */
    private val liveSender: RawSmtpSender by lazy { RawSmtpSender(settings) }

    /**
     * Preview URL for the last message sent through the live transport
     * (Ethereal.email returns one via the DATA-acceptance reply; real
     * providers don't, so this is null outside sandbox testing).
     */
    val lastPreviewUrl: String?
        get() = liveSender.lastPreviewUrl

    override suspend fun send(message: AlertMessage) {
        val active = sender ?: liveSender
        active.send(
            MailMessage(
                from = settings.from,
                to = settings.to,
                subject = message.text,
                text = message.text,
                attachment = message.snapshot,
            ),
        )
        (active as? RawSmtpSender)?.lastPreviewUrl?.let {
            Log.d("EmailChannel", "Preview URL: $it")
        }
    }

    override suspend fun sendTest() {
        (sender ?: liveSender).send(
            MailMessage(
                from = settings.from,
                to = settings.to,
                subject = "Security Cam: test alert",
                text = "Security Cam: test alert",
                attachment = testSnapshot(),
            ),
        )
    }

    override fun validate(): String? {
        if (settings.host.isEmpty()) return "SMTP host is required"
        if (settings.username.isEmpty() || settings.password.isEmpty()) {
            return "Username and password are required"
        }
        // 587/465 have exactly one correct mode each; anything else fails at
        // the TLS handshake with a cryptic SSL error, so block it up-front.
        // Custom ports stay unrestricted (e.g. Mailtrap 2525, Mailpit 1025).
        if (settings.port == 587 && settings.useTls) {
            return "Port 587 uses STARTTLS — turn Implicit TLS off"
        }
        if (settings.port == 465 && !settings.useTls) {
            return "Port 465 uses implicit TLS — turn Implicit TLS on"
        }
        if (!EMAIL_REGEX.matches(settings.from)) return "From address is invalid"
        if (!EMAIL_REGEX.matches(settings.to)) return "To address is invalid"
        return null
    }

    companion object {
        // ^[^@\s]+@[^@\s]+\.[^@\s]+$
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

/**
 * Minimal SMTP client (plain, SSL, or STARTTLS with AUTH LOGIN), with
 * multipart/mixed JPEG attachments. Enough for alert delivery against real
 * providers.
 */
class RawSmtpSender(
    private val settings: EmailChannelSettings,
    private val socketFactory: SocketFactory? = null,
) : MailSender {

    /** Preview URL for the last message sent through this instance, if any. */
    var lastPreviewUrl: String? = null
        private set

    companion object {
        /** Base for sandbox preview links (Ethereal.email message pages). */
        const val PREVIEW_BASE_URL = "https://ethereal.email/message/"

        /** RFC 5322 Date header format (`Thu, 11 Sep 2026 01:55:00 GMT`). */
        internal val RFC_5322_NOW: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME

        private val MSGID_REGEX = Regex("""MSGID=([^\s\]]+)""")

        /**
         * Extracts the sandbox preview URL from the server's DATA-acceptance
         * reply (Ethereal answers `250 Ok: queued as [STATUS=SUCCESS
         * MSGID=<id>]`); null when the reply carries no message id.
         */
        fun previewUrlFromDataReply(reply: String): String? {
            val id = MSGID_REGEX.find(reply)?.groupValues?.get(1) ?: return null
            return PREVIEW_BASE_URL + id
        }

        /**
         * Actionable replacement for a raw TLS handshake failure (used when
         * the toggle/port pairing slips past validation, e.g. custom ports).
         */
        fun tlsFailureHint(settings: EmailChannelSettings): String =
            if (settings.useTls) {
                "TLS handshake failed — ${settings.host}:${settings.port} did not speak TLS; " +
                    "for port 587 turn Implicit TLS off (STARTTLS), for 465 keep it on"
            } else {
                "STARTTLS failed on ${settings.host}:${settings.port}; " +
                    "for port 465 turn Implicit TLS on, for 587 keep it off"
            }

        /**
         * Actionable replacement for a raw SMTP failure reply. A 535 during
         * AUTH means the server rejected the credentials (wrong or expired —
         * sandbox accounts are short-lived); anything else keeps the generic
         * step + code text. Never includes command text: AUTH lines carry
         * base64 credentials.
         */
        fun smtpErrorHint(step: String, code: Int): String =
            if (code == 535 && step.startsWith("AUTH")) {
                "Authentication failed (535) during $step — check the SMTP username and password " +
                    "(sandbox accounts expire; create a fresh one if needed)"
            } else {
                "SMTP $step failed ($code)"
            }
    }

    override suspend fun send(message: MailMessage): Unit = withContext(Dispatchers.IO) {
        lastPreviewUrl = null
        try {
            val conn = Connection(settings.host, settings.port, settings.useTls, socketFactory)
            try {
                conn.readReply(220..229, "greeting")
                conn.cmd("EHLO level2", 250..259)
                if (!settings.useTls) {
                    conn.cmd("STARTTLS", 220..229)
                    conn.upgradeToTls(settings.host)
                    conn.cmd("EHLO level2", 250..259)
                }
                conn.cmd("AUTH LOGIN", 330..339)
                conn.cmd(
                    Base64.getEncoder().encodeToString(settings.username.toByteArray()),
                    330..339,
                    step = "AUTH username",
                )
                conn.cmd(
                    Base64.getEncoder().encodeToString(settings.password.toByteArray()),
                    230..239,
                    step = "AUTH password",
                )
                conn.cmd("MAIL FROM:<${settings.from}>", 250..259)
                conn.cmd("RCPT TO:<${settings.to}>", 250..259)
                conn.cmd("DATA", 350..359)
                val dataReply = conn.writeData(renderMessage(message))
                lastPreviewUrl = previewUrlFromDataReply(dataReply)
                conn.cmd("QUIT", 220..259)
            } finally {
                conn.close()
            }
        } catch (e: SSLException) {
            // Wrong mode for the port (e.g. implicit TLS against a STARTTLS
            // port): the raw error ("Unable to parse TLS packet header")
            // names nothing actionable, so say what to flip instead.
            throw IllegalStateException(tlsFailureHint(settings), e)
        }
    }

    /**
     * Renders the DATA content. The closing dot MUST be followed by CRLF
     * (`<CR><LF>.<CR><LF>` ends DATA) — without it the server keeps waiting
     * and the read times out.
     */
    internal fun renderMessage(m: MailMessage): String {
        val subject = if (m.subject.all { it.code in 32..126 }) m.subject
        else "=?utf-8?B?" + Base64.getEncoder().encodeToString(m.subject.toByteArray()) + "?="
        val topHeaders = buildString {
            append("From: <").append(m.from).append(">\r\n")
            append("To: <").append(m.to).append(">\r\n")
            append("Subject: ").append(subject).append("\r\n")
            append("Date: ").append(RFC_5322_NOW.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))).append("\r\n")
            append("Message-ID: <").append(java.util.UUID.randomUUID()).append("@level2>\r\n")
            append("MIME-Version: 1.0\r\n")
        }
        val body = if (m.attachment == null) {
            topHeaders + "Content-Type: text/plain; charset=utf-8\r\n\r\n" + m.text
        } else {
            renderMultipart(topHeaders, m.text, m.attachment)
        }
        return body.lineSequence().joinToString("\r\n") { line -> if (line.startsWith(".")) ".$line" else line } + "\r\n.\r\n"
    }

    private fun renderMultipart(topHeaders: String, text: String, attachment: Snapshot): String {
        // Filenames derive from user-controlled camera names: keep header
        // metacharacters out of the quoted-string.
        val safeName = attachment.name.replace(Regex("[\\r\\n\"]"), "")
        val boundary = "level2-" + java.util.UUID.randomUUID()
        val encoded = Base64.getMimeEncoder().encodeToString(attachment.bytes)
        return topHeaders +
            "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n" +
            "--$boundary\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
            text + "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: ${attachment.mimeType}; name=\"$safeName\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"$safeName\"\r\n\r\n" +
            encoded + "\r\n" +
            "--$boundary--"
    }

    private class Connection(
        host: String,
        port: Int,
        useTls: Boolean,
        socketFactory: SocketFactory?,
    ) {
        private var socket: Socket =
            if (useTls) {
                (socketFactory as? SSLSocketFactory ?: SSLSocketFactory.getDefault())
                    .createSocket(host, port)
            } else {
                Socket().apply { connect(InetSocketAddress(host, port), 15_000) }
            }
        private var reader: BufferedReader
        private var writer: BufferedWriter

        init {
            socket.soTimeout = 15_000
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        }

        /** Wraps the current plain connection in TLS after a STARTTLS reply. */
        fun upgradeToTls(host: String) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = factory.createSocket(socket, host, socket.getPort(), true)
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        }

        fun readReply(expected: IntRange, step: String): String {
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: error("SMTP connection closed during $step")
                lines.add(line)
                if (line.length <= 3 || line[3] != '-') break
            }
            val code = lines.last().take(3).toIntOrNull()
                ?: error("SMTP malformed reply during $step: ${lines.last()}")
            // Codes only, never content: keeps AUTH material out of logcat.
            Log.d("EmailChannel", "SMTP $step -> $code")
            check(code in expected) { smtpErrorHint(step, code) }
            return lines.joinToString("\n")
        }

        fun cmd(command: String, expected: IntRange, step: String? = null): String {
            writer.write(command)
            writer.write("\r\n")
            writer.flush()
            return readReply(expected, step ?: command.takeWhile { it != ' ' })
        }

        fun writeData(data: String): String {
            writer.write(data)
            writer.flush()
            return readReply(250..259, "DATA acceptance")
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}