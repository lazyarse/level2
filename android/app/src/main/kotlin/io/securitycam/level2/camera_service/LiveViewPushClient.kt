package io.securitycam.level2.camera_service

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URI

class LiveViewPushClient(
    private val relayUrl: String,
    private val username: String,
    private val password: String,
    private val packetizer: RtpPacketizer,
) : LiveViewOutput {

    companion object {
        private const val TAG = "LiveViewPushClient"
        private const val RTSP_VERSION = "RTSP/1.0"
        private const val RTP_VERSION = 2
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputReader: BufferedReader? = null
    private var rtpSocket: DatagramSocket? = null
    private var running = false
    private var session: String = ""
    private var cseq: Int = 0
    private var remoteHost: String = ""
    private var remoteVideoPort: Int = 0
    private var remoteAudioPort: Int = 0
    private var path: String = "/"
    private var videoSeqNum: Int = 0
    private var audioSeqNum: Int = 0

    @Synchronized
    fun connect() {
        if (running) {
            Log.w(TAG, "Already connected")
            return
        }
        try {
            val uri = URI(relayUrl)
            val host = uri.host ?: throw IllegalArgumentException("No host in relayUrl")
            val port = if (uri.port > 0) uri.port else 554
            path = uri.path ?: "/"
            if (path.isEmpty()) path = "/"

            remoteHost = InetAddress.getByName(host).hostAddress ?: host

            socket = Socket(host, port).apply {
                soTimeout = 5000
            }
            outputStream = socket!!.getOutputStream()
            inputReader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            rtpSocket = DatagramSocket()
            rtpSocket!!.soTimeout = 5000

            running = true
            cseq = 0

            val sdp = buildSdp()
            sendRequest("ANNOUNCE", path, mapOf("Content-Type" to "application/sdp"), sdp)

            val videoSetupResponse = sendRequest(
                "SETUP", "$path/trackID=0",
                mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=50000-50001"),
                null
            )
            if (videoSetupResponse != null) {
                val (vLow, vHigh) = parseTransport(videoSetupResponse)
                remoteVideoPort = vLow
            }

            val audioSetupResponse = sendRequest(
                "SETUP", "$path/trackID=1",
                mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=50002-50003"),
                null
            )
            if (audioSetupResponse != null) {
                val (aLow, aHigh) = parseTransport(audioSetupResponse)
                remoteAudioPort = aLow
            }

            sendRequest("RECORD", path, emptyMap(), null)
            Log.i(TAG, "Connected to relay at $relayUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            disconnect()
            throw e
        }
    }

    @Synchronized
    fun disconnect() {
        if (!running) return
        running = false
        try {
            sendRequest("TEARDOWN", path, emptyMap(), null)
        } catch (e: Exception) {
            Log.w(TAG, "TEARDOWN failed", e)
        }
        try {
            inputReader?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            rtpSocket?.close()
        } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputReader = null
        rtpSocket = null
        session = ""
        Log.i(TAG, "Disconnected")
    }

    override fun onVideoFrame(nalUnits: List<ByteArray>, timestampUs: Long, isKeyFrame: Boolean) {
        if (!running) return
        try {
            for (nalUnit in nalUnits) {
                val packets = packetizer.packetize(nalUnit, timestampUs, isKeyFrame)
                for (packet in packets) {
                    sendRtpViaUdp(packet.data, remoteHost, remoteVideoPort)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send video frame", e)
        }
    }

    override fun onAudioFrame(aacFrame: ByteArray, timestampUs: Long) {
        if (!running) return
        try {
            val rtpPacket = buildAudioRtpPacket(aacFrame, timestampUs)
            sendRtpViaUdp(rtpPacket, remoteHost, remoteAudioPort)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame", e)
        }
    }

    private fun buildAudioRtpPacket(aacFrame: ByteArray, timestampUs: Long): ByteArray {
        val rtpTimestamp = ((timestampUs * 90000L) / 1_000_000L).toInt()
        val header = ByteArray(12)
        header[0] = ((RTP_VERSION shl 6) or (0 shl 5) or 0).toByte()
        header[1] = 97.toByte()
        // Sequence number
        header[2] = ((audioSeqNum shr 8) and 0xFF).toByte()
        header[3] = (audioSeqNum and 0xFF).toByte()
        audioSeqNum = (audioSeqNum + 1) and 0xFFFF
        // Timestamp
        header[4] = ((rtpTimestamp shr 24) and 0xFF).toByte()
        header[5] = ((rtpTimestamp shr 16) and 0xFF).toByte()
        header[6] = ((rtpTimestamp shr 8) and 0xFF).toByte()
        header[7] = (rtpTimestamp and 0xFF).toByte()
        // SSRC
        val ssrc = hashCode() and 0x7FFFFFFF
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        val auHeaderLength = 2
        val auHeader = ByteArray(auHeaderLength)
        val auSize = aacFrame.size * 8
        auHeader[0] = ((auSize shr 8) and 0xFF).toByte()
        auHeader[1] = (auSize and 0xFF).toByte()
        val packet = ByteArray(header.size + auHeader.size + aacFrame.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(auHeader, 0, packet, header.size, auHeader.size)
        System.arraycopy(aacFrame, 0, packet, header.size + auHeader.size, aacFrame.size)
        return packet
    }

    @Synchronized
    private fun sendRequest(
        method: String,
        path: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: String? = null
    ): String? {
        val os = outputStream ?: return null
        val reader = inputReader ?: return null
        cseq++
        val sb = StringBuilder()
        sb.append("$method $path $RTSP_VERSION\r\n")
        sb.append("CSeq: $cseq\r\n")
        if (username.isNotEmpty() && password.isNotEmpty()) {
            val credentials = "$username:$password"
            val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
            sb.append("Authorization: Basic $encoded\r\n")
        }
        if (session.isNotEmpty()) {
            sb.append("Session: $session\r\n")
        }
        for ((key, value) in extraHeaders) {
            sb.append("$key: $value\r\n")
        }
        if (body != null) {
            sb.append("Content-Length: ${body.toByteArray().size}\r\n")
        }
        sb.append("\r\n")
        if (body != null) {
            sb.append(body)
        }
        val request = sb.toString()
        os.write(request.toByteArray())
        os.flush()

        val response = readResponse(reader)
        val (_, headers) = parseResponse(response)
        val sessionHeader = headers["session"]
        if (sessionHeader != null && session.isEmpty()) {
            session = sessionHeader.split(";").first().trim()
        }
        Log.d(TAG, "< $method -> ${response.lines().firstOrNull()}")
        return response
    }

    private fun readResponse(reader: BufferedReader): String {
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line).append("\r\n")
            if (line.isNullOrEmpty()) break
        }
        return sb.toString()
    }

    private fun parseResponse(response: String): Pair<Int, Map<String, String>> {
        val lines = response.lines()
        var statusCode = 0
        val headers = mutableMapOf<String, String>()
        for (line in lines) {
            if (line.startsWith("RTSP/1.0")) {
                val parts = line.split(" ", limit = 3)
                statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
            } else if (line.contains(":")) {
                val idx = line.indexOf(':')
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        return Pair(statusCode, headers)
    }

    private fun parseTransport(setupResponse: String): Pair<Int, Int> {
        val (_, headers) = parseResponse(setupResponse)
        val transport = headers["transport"] ?: return Pair(0, 0)
        val clientPortMatch = Regex("client_port=(\\d+)-(\\d+)").find(transport)
        if (clientPortMatch != null) {
            val low = clientPortMatch.groupValues[1].toIntOrNull() ?: 0
            val high = clientPortMatch.groupValues[2].toIntOrNull() ?: 0
            return Pair(low, high)
        }
        return Pair(0, 0)
    }

    private fun buildSdp(): String {
        return StringBuilder().apply {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 127.0.0.1\r\n")
            append("s=LiveView\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            append("a=fmtp:96 packetization-mode=1\r\n")
            append("a=control:trackID=0\r\n")
            append("m=audio 0 RTP/AVP 97\r\n")
            append("a=rtpmap:97 MPEG4-GENERIC/44100/2\r\n")
            append("a=fmtp:97 streamtype=5; profile-level-id=1; mode=AAC-hbr; sizelength=13; indexlength=3; indexdeltalength=3\r\n")
            append("a=control:trackID=1\r\n")
        }.toString()
    }

    private fun sendRtpViaUdp(data: ByteArray, host: String, port: Int) {
        val sock = rtpSocket ?: return
        val address = InetAddress.getByName(host)
        val packet = DatagramPacket(data, data.size, address, port)
        sock.send(packet)
    }
}
