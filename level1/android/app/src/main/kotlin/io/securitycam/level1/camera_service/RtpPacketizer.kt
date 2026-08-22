package io.securitycam.level1.camera_service

import java.security.SecureRandom

class RtpPacketizer(
    private val mtu: Int = DEFAULT_MTU,
    private val clockRate: Int = 90_000
) {
    var payloadType: Int = H264_PAYLOAD_TYPE
    private var sequenceNumber: Int = SecureRandom().nextInt(65536)
    private var ssrc: Long = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private val maxPayload = mtu - RTP_HEADER_SIZE

    fun setParameterSets(spsData: ByteArray, ppsData: ByteArray) {
        sps = spsData.copyOf()
        pps = ppsData.copyOf()
    }

    fun packetize(nalUnit: ByteArray, timestampUs: Long, isKeyFrame: Boolean): List<RtpPacket> {
        val rtpTimestamp = timestampUs * clockRate / 1_000_000L
        val packets = mutableListOf<RtpPacket>()

        if (isKeyFrame) {
            val currentSps = sps
            val currentPps = pps
            if (currentSps != null && currentPps != null) {
                packets.add(buildStapAPacket(currentSps, currentPps, rtpTimestamp))
            }
        }

        if (nalUnit.size <= maxPayload) {
            packets.add(buildSingleNalPacket(nalUnit, rtpTimestamp, marker = true))
        } else {
            packets.addAll(fragmentViaFuA(nalUnit, rtpTimestamp))
        }

        return packets
    }

    private fun buildRtpHeader(marker: Boolean, timestamp: Long): ByteArray {
        val header = ByteArray(RTP_HEADER_SIZE)
        header[0] = 0x80.toByte()
        header[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        header[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        header[3] = (sequenceNumber and 0xFF).toByte()
        header[4] = ((timestamp shr 24) and 0xFF).toByte()
        header[5] = ((timestamp shr 16) and 0xFF).toByte()
        header[6] = ((timestamp shr 8) and 0xFF).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        return header
    }

    private fun buildSingleNalPacket(nalUnit: ByteArray, timestamp: Long, marker: Boolean): RtpPacket {
        val header = buildRtpHeader(marker, timestamp)
        val packetData = header + nalUnit
        return RtpPacket(data = packetData, timestamp = timestamp)
    }

    private fun buildStapAPacket(spsData: ByteArray, ppsData: ByteArray, timestamp: Long): RtpPacket {
        val stapPayload = ByteArray(2 + spsData.size + 2 + ppsData.size)
        var offset = 0
        stapPayload[offset++] = ((spsData.size shr 8) and 0xFF).toByte()
        stapPayload[offset++] = (spsData.size and 0xFF).toByte()
        System.arraycopy(spsData, 0, stapPayload, offset, spsData.size)
        offset += spsData.size
        stapPayload[offset++] = ((ppsData.size shr 8) and 0xFF).toByte()
        stapPayload[offset++] = (ppsData.size and 0xFF).toByte()
        System.arraycopy(ppsData, 0, stapPayload, offset, ppsData.size)

        val nri = (spsData[0].toInt() and 0x60)
        val stapHeader = (nri or 24).toByte()
        val fullPayload = ByteArray(1 + stapPayload.size)
        fullPayload[0] = stapHeader
        System.arraycopy(stapPayload, 0, fullPayload, 1, stapPayload.size)

        val header = buildRtpHeader(marker = false, timestamp = timestamp)
        val packetData = header + fullPayload
        return RtpPacket(data = packetData, timestamp = timestamp)
    }

    private fun fragmentViaFuA(nalUnit: ByteArray, timestamp: Long): List<RtpPacket> {
        val nri = nalUnit[0].toInt() and 0x60
        val nalType = nalUnit[0].toInt() and 0x1F
        val chunkSize = maxPayload - 1

        val packets = mutableListOf<RtpPacket>()
        var offset = 1
        var isFirst = true

        while (offset < nalUnit.size) {
            val remaining = nalUnit.size - offset
            val currentChunkSize = minOf(chunkSize, remaining)
            val isLast = offset + currentChunkSize >= nalUnit.size

            val fuIndicator = (nri or 28).toByte()
            var fuHeader = (nalType and 0x1F).toByte()
            if (isFirst) fuHeader = (fuHeader.toInt() or 0x80).toByte()
            if (isLast) fuHeader = (fuHeader.toInt() or 0x40).toByte()

            val header = buildRtpHeader(marker = isLast, timestamp = timestamp)
            val chunk = ByteArray(header.size + 2 + currentChunkSize)
            System.arraycopy(header, 0, chunk, 0, header.size)
            chunk[header.size] = fuIndicator
            chunk[header.size + 1] = fuHeader
            System.arraycopy(nalUnit, offset, chunk, header.size + 2, currentChunkSize)

            packets.add(RtpPacket(data = chunk, timestamp = timestamp))
            offset += currentChunkSize
            isFirst = false
        }

        return packets
    }

    companion object {
        const val RTP_HEADER_SIZE = 12
        const val DEFAULT_MTU = 1400
        const val H264_PAYLOAD_TYPE = 96
    }
}

data class RtpPacket(val data: ByteArray, val timestamp: Long = 0) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtpPacket) return false
        return data.contentEquals(other.data) && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
