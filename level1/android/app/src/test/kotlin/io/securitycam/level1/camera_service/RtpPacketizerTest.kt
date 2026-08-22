package io.securitycam.level1.camera_service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketizerTest {

    private val packetizer = RtpPacketizer(mtu = 1400)

    @Test
    fun singleNalPacketHasCorrectHeader() {
        val nal = byteArrayOf(0x65, 0x01, 0x02, 0x03) // IDR NAL type 5
        val packets = packetizer.packetize(nal, timestampUs = 100_000, isKeyFrame = true)

        // First packet is STAP-A (SPS+PPS), second is the NAL
        assertTrue(packets.size >= 1)
        // Skip STAP-A if present
        val nalPacket = packets.last()
        val header = nalPacket.data

        // V=2, P=0, X=0, CC=0
        assertEquals(0x80.toByte(), header[0])
        // Marker=true, PT=96
        assertEquals((0x80 or 96).toByte(), header[1])
        // Timestamp
        val expectedTs = 100_000L * 90_000 / 1_000_000 // 9000
        assertEquals((expectedTs shr 24 and 0xFF).toByte(), header[4])
        assertEquals((expectedTs shr 16 and 0xFF).toByte(), header[5])
        assertEquals((expectedTs shr 8 and 0xFF).toByte(), header[6])
        assertEquals((expectedTs and 0xFF).toByte(), header[7])
        // NAL unit is the payload
        assertArrayEquals(nal, header.copyOfRange(12, header.size))
    }

    @Test
    fun stapAPrecedesKeyFrame() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x0A)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        packetizer.setParameterSets(sps, pps)

        val idr = byteArrayOf(0x65, 0x01)
        val packets = packetizer.packetize(idr, timestampUs = 0, isKeyFrame = true)

        assertEquals(2, packets.size)

        // First packet is STAP-A
        val stapA = packets[0].data
        assertEquals(0x80.toByte(), stapA[0]) // V=2
        // Marker=false for STAP-A
        assertFalse(stapA[1].toInt() and 0x80 != 0)
        // PT=96
        assertEquals(96, stapA[1].toInt() and 0x7F)

        // STAP-A indicator: NRI from SPS, type=24
        val indicator = stapA[12].toInt() and 0xFF
        assertEquals(24, indicator and 0x1F) // type 24
        assertEquals(sps[0].toInt() and 0x60, indicator and 0x60) // NRI from SPS

        // STAP-A payload: sps_len(2) + sps + pps_len(2) + pps
        val payload = stapA.copyOfRange(13, stapA.size)
        assertEquals(sps.size, (payload[0].toInt() shl 8 or payload[1].toInt()))
        assertArrayEquals(sps, payload.copyOfRange(2, 2 + sps.size))
        val ppsOffset = 2 + sps.size
        assertEquals(pps.size, (payload[ppsOffset].toInt() shl 8 or payload[ppsOffset + 1].toInt()))
        assertArrayEquals(pps, payload.copyOfRange(ppsOffset + 2, ppsOffset + 2 + pps.size))
    }

    @Test
    fun noStapAOnNonKeyFrame() {
        val sps = byteArrayOf(0x67, 0x42)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        packetizer.setParameterSets(sps, pps)

        val pSlice = byteArrayOf(0x41, 0x01, 0x02)
        val packets = packetizer.packetize(pSlice, timestampUs = 50_000, isKeyFrame = false)

        assertEquals(1, packets.size)
    }

    @Test
    fun fuAFragmentationForLargeNal() {
        val mtu = 100
        val pkt = RtpPacketizer(mtu = mtu)
        val bigNal = ByteArray(300) { it.toByte() } // 300 bytes > mtu - 12 = 88
        val packets = pkt.packetize(bigNal, timestampUs = 0, isKeyFrame = false)

        assertTrue(packets.size > 1)

        // First fragment: S flag set, marker=false
        val first = packets[0].data
        assertEquals(0x80.toByte(), first[0]) // RTP V=2
        assertFalse(first[1].toInt() and 0x80 != 0) // marker=false
        val fuIndicator = first[12].toInt() and 0xFF
        assertEquals(28, fuIndicator and 0x1F) // FU-A type
        val fuHeader = first[13].toInt() and 0xFF
        assertTrue(fuHeader and 0x80 != 0) // S flag
        assertFalse(fuHeader and 0x40 != 0) // E flag not set

        // Last fragment: E flag set, marker=true
        val last = packets.last().data
        val lastFuHeader = last[13].toInt() and 0xFF
        assertTrue(lastFuHeader and 0x40 != 0) // E flag
        assertTrue(last[1].toInt() and 0x80 != 0) // marker=true

        // All fragments preserve NAL type
        val nalType = bigNal[0].toInt() and 0x1F
        packets.forEach { p ->
            assertEquals(nalType, p.data[13].toInt() and 0x1F)
        }

        // Reassemble and check all bytes are present (FU-A replaces the NAL header byte)
        val reassembled = mutableListOf<ByteArray>()
        packets.forEach { p ->
            val chunk = p.data.copyOfRange(14, p.data.size)
            reassembled.add(chunk)
        }
        val total = reassembled.sumOf { it.size }
        assertEquals(bigNal.size - 1, total)
    }

    @Test
    fun sequenceNumberIncrements() {
        val nal = byteArrayOf(0x65, 0x01)
        val p1 = packetizer.packetize(nal, 0, false)
        val p2 = packetizer.packetize(nal, 100_000, false)

        val seq1 = (p1[0].data[2].toInt() shl 8) or p1[0].data[3].toInt()
        val seq2 = (p2[0].data[2].toInt() shl 8) or p2[0].data[3].toInt()
        assertEquals(1, seq2 - seq1)
    }

    @Test
    fun rtpPacketEqualsHashcode() {
        val a = RtpPacket(byteArrayOf(1, 2, 3), 100)
        val b = RtpPacket(byteArrayOf(1, 2, 3), 100)
        val c = RtpPacket(byteArrayOf(1, 2, 4), 100)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
