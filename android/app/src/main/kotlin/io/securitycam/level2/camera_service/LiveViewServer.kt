package io.securitycam.level2.camera_service

import android.util.Log
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

class LiveViewServer(
    private val port: Int,
    private val username: String,
    private val password: String,
    private val videoStream: () -> Unit,
    private val stopStream: () -> Unit,
    private val requestKeyFrame: () -> Unit = {},
    private val talkBackEnabled: Boolean = false,
    private val speakerOutput: SpeakerOutput? = null,
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var running = false
    private var session: String = randomSession()
    private var cseq: String = ""
    private var rtpOutput: OutputStream? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    @Synchronized
    fun setParameterSets(spsData: ByteArray, ppsData: ByteArray) {
        sps = spsData.copyOf()
        pps = ppsData.copyOf()
    }

    @Synchronized
    fun sendRtpPacket(data: ByteArray) {
        val out = rtpOutput ?: return
        try {
            out.write('$'.code)
            out.write(0) // channel 0
            out.write((data.size shr 8) and 0xFF)
            out.write(data.size and 0xFF)
            out.write(data)
            out.flush()
        } catch (_: Exception) {
        }
    }

    fun start() {
        running = true
        Thread({
            try {
                serverSocket = ServerSocket(port)
                Log.i("LiveViewServer", "Listening on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    Log.i("LiveViewServer", "Client connected: ${client.inetAddress}")
                    clientSocket = client
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e("LiveViewServer", "Server error", e)
            }
        }, "LiveViewServer").start()
    }

    fun stop() {
        running = false
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientSocket = null
        serverSocket = null
        rtpOutput = null
    }

    fun isRunning(): Boolean = running

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            while (running) {
                val request = readRequest(input) ?: break
                handleRequest(request)
            }
        } catch (_: Exception) {
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
            rtpOutput = null
            clientSocket = null
        }
    }

    private fun readRequest(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isNotEmpty()) sb.toString() else null
            if (b == '$'.code) {
                // Interleaved RTP frame — read channel (1) + length (2) + payload
                val channel = input.read()
                if (channel < 0) return if (sb.isNotEmpty()) sb.toString() else null
                val lenHi = input.read()
                val lenLo = input.read()
                if (lenHi < 0 || lenLo < 0) return if (sb.isNotEmpty()) sb.toString() else null
                val length = (lenHi shl 8) or lenLo
                if (length > 0) {
                    val payload = readExact(input, length) ?: break
                    if (channel == 2 && talkBackEnabled) {
                        handleIncomingAudio(payload)
                    }
                }
                continue
            }
            val prev = if (sb.length >= 2) sb[sb.length - 2].code else 0
            val prevPrev = if (sb.length >= 3) sb[sb.length - 3].code else 0
            sb.append(b.toChar())
            // Detect end-of-headers: \r\n\r\n
            if (b == '\n'.code && prev == '\r'.code && prevPrev == '\n'.code) {
                return sb.toString()
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(buf, off, n - off)
            if (read < 0) return null
            off += read
        }
        return buf
    }

    private fun handleIncomingAudio(payload: ByteArray) {
        try {
            val pcm = PcmuDecoder.decode(payload)
            speakerOutput?.feedPcm(pcm)
        } catch (e: Exception) {
            Log.w("LiveViewServer", "Talk-back decode failed", e)
        }
    }

    private fun handleRequest(request: String) {
        val lines = request.split("\r\n", "\n")
        if (lines.isEmpty()) return
        val firstLine = lines[0].trim()
        val parts = firstLine.split(" ")
        if (parts.size < 3) return
        val method = parts[0].uppercase()
        val path = parts[1]
        val headers = parseHeaders(lines.drop(1))
        cseq = headers["CSeq"] ?: headers["cseq"] ?: ""

        if (!checkAuth(headers["Authorization"])) {
            send401()
            return
        }

        when (method) {
            "OPTIONS" -> handleOptions()
            "DESCRIBE" -> handleDescribe(path)
            "SETUP" -> handleSetup(path, headers["Transport"] ?: "")
            "PLAY" -> handlePlay()
            "TEARDOWN" -> handleTeardown()
            else -> sendResponse(405, "Method Not Allowed", emptyMap())
        }
    }

    private fun handleOptions() {
        val headers = mapOf(
            "Public" to "DESCRIBE, SETUP, PLAY, TEARDOWN",
            "CSeq" to cseq,
        )
        sendResponse(200, "OK", headers)
    }

    private fun handleDescribe(path: String) {
        val sdp = buildSdp()
        val headers = mapOf(
            "CSeq" to cseq,
            "Content-Type" to "application/sdp",
            "Content-Length" to sdp.toByteArray().size.toString(),
        )
        sendResponse(200, "OK", headers, sdp)
    }

    private fun handleSetup(path: String, transport: String) {
        val transportHeader = if (talkBackEnabled) {
            "RTP/AVP/TCP;unicast;interleaved=0-1,2-3;session=$session"
        } else {
            "RTP/AVP/TCP;unicast;interleaved=0-1;session=$session"
        }
        val headers = mapOf(
            "CSeq" to cseq,
            "Transport" to transportHeader,
            "Session" to session,
        )
        sendResponse(200, "OK", headers)
    }

    private fun handlePlay() {
        val headers = mapOf(
            "CSeq" to cseq,
            "Session" to session,
        )
        sendResponse(200, "OK", headers)
        rtpOutput = clientSocket?.getOutputStream()
        requestKeyFrame()
        videoStream()
    }

    private fun handleTeardown() {
        val headers = mapOf(
            "CSeq" to cseq,
            "Session" to session,
        )
        sendResponse(200, "OK", headers)
        stopStream()
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        clientSocket = null
        rtpOutput = null
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
        }
        return map
    }

    private fun checkAuth(authorization: String?): Boolean {
        if (username.isEmpty() && password.isEmpty()) return true
        if (authorization == null) return false
        if (!authorization.startsWith("Basic ")) return false
        val encoded = authorization.substringAfter("Basic ").trim()
        return try {
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
            decoded == "$username:$password"
        } catch (_: Exception) {
            false
        }
    }

    private fun send401() {
        val headers = mapOf(
            "CSeq" to cseq,
            "WWW-Authenticate" to "Basic realm=\"LiveView\"",
        )
        sendResponse(401, "Unauthorized", headers)
    }

    private fun buildSdp(): String {
        val currentSps = sps
        val currentPps = pps
        val sb = StringBuilder()
        sb.appendLine("v=0")
        sb.appendLine("o=- $session 1 IN IP4 0.0.0.0")
        sb.appendLine("s=Live View")
        sb.appendLine("t=0 0")
        sb.appendLine("m=video 0 RTP/AVP 96")
        sb.appendLine("c=IN IP4 0.0.0.0")
        sb.appendLine("a=rtpmap:96 H264/90000")
        val fmtp = if (currentSps != null && currentPps != null) {
            val spsB64 = android.util.Base64.encodeToString(currentSps, android.util.Base64.NO_WRAP)
            val ppsB64 = android.util.Base64.encodeToString(currentPps, android.util.Base64.NO_WRAP)
            "a=fmtp:96 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=$spsB64,$ppsB64"
        } else {
            "a=fmtp:96 packetization-mode=1;profile-level-id=42C01F"
        }
        sb.appendLine(fmtp)
        sb.appendLine("a=control:track1")
        if (talkBackEnabled) {
            sb.appendLine("m=audio 0 RTP/AVP 0")
            sb.appendLine("a=rtpmap:0 PCMU/8000")
            sb.appendLine("a=control:track2")
        }
        return sb.toString()
    }

    private fun sendResponse(code: Int, reason: String, headers: Map<String, String>, body: String? = null) {
        val client = clientSocket ?: return
        try {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 $code $reason\r\n")
            for ((k, v) in headers) {
                sb.append("$k: $v\r\n")
            }
            sb.append("\r\n")
            if (body != null) sb.append(body)
            client.getOutputStream().write(sb.toString().toByteArray())
            client.getOutputStream().flush()
        } catch (_: Exception) {
        }
    }

    private fun randomSession(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
