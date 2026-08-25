package io.securitycam.level2.channels

import io.securitycam.level2.core.AlertMessage
import io.securitycam.level2.core.ChannelSettings
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.SocketFactory
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
)

fun interface MailSender {
    suspend fun send(message: MailMessage)
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
) : io.securitycam.level2.core.Channel {

    override val type: String get() = "email"

    override suspend fun send(message: AlertMessage) {
        (sender ?: RawSmtpSender(settings)).send(
            MailMessage(from = settings.from, to = settings.to, subject = message.text, text = message.text),
        )
    }

    override suspend fun sendTest() {
        (sender ?: RawSmtpSender(settings)).send(
            MailMessage(
                from = settings.from,
                to = settings.to,
                subject = "Security Cam: test alert",
                text = "Security Cam: test alert",
            ),
        )
    }

    override fun validate(): String? {
        if (settings.host.isEmpty()) return "SMTP host is required"
        if (settings.username.isEmpty() || settings.password.isEmpty()) {
            return "Username and password are required"
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
 * Minimal SMTP client (plain, SSL, or STARTTLS with AUTH LOGIN). Enough for
 * alert delivery against real providers; no attachments.
 */
class RawSmtpSender(
    private val settings: EmailChannelSettings,
    private val socketFactory: SocketFactory? = null,
) : MailSender {

    override suspend fun send(message: MailMessage): Unit = withContext(Dispatchers.IO) {
        var conn = Connection(settings.host, settings.port, settings.useTls, socketFactory)
        try {
            conn.readReply(220..229, "greeting")
            conn.cmd("EHLO level2", 250..259)
            if (!settings.useTls) {
                conn.cmd("STARTTLS", 220..229)
                conn.upgradeToTls(settings.host)
                conn.cmd("EHLO level2", 250..259)
            }
            conn.cmd("AUTH LOGIN", 330..339)
            conn.cmd(Base64.getEncoder().encodeToString(settings.username.toByteArray()), 330..339)
            conn.cmd(Base64.getEncoder().encodeToString(settings.password.toByteArray()), 230..239)
            conn.cmd("MAIL FROM:<${settings.from}>", 250..259)
            conn.cmd("RCPT TO:<${settings.to}>", 250..259)
            conn.cmd("DATA", 350..359)
            conn.writeData(renderMessage(message))
            conn.cmd("QUIT", 220..259)
        } finally {
            conn.close()
        }
    }

    private fun renderMessage(m: MailMessage): String {
        val subject = if (m.subject.all { it.code in 32..126 }) m.subject
        else "=?utf-8?B?" + Base64.getEncoder().encodeToString(m.subject.toByteArray()) + "?="
        return buildString {
            append("From: <").append(m.from).append(">\r\n")
            append("To: <").append(m.to).append(">\r\n")
            append("Subject: ").append(subject).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("\r\n")
            append(m.text)
        }.lineSequence().joinToString("\r\n") { line -> if (line.startsWith(".")) ".$line" else line } + "\r\n."
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
            check(code in expected) { "SMTP $step failed ($code)" }
            return lines.joinToString("\n")
        }

        fun cmd(command: String, expected: IntRange): String {
            writer.write(command)
            writer.write("\r\n")
            writer.flush()
            return readReply(expected, command.takeWhile { it != ' ' })
        }

        fun writeData(data: String) {
            writer.write(data)
            writer.flush()
            readReply(250..259, "DATA acceptance")
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}