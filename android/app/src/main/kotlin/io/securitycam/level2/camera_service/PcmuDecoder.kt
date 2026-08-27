package io.securitycam.level2.camera_service

/** Decodes PCMU (G.711 µ-law) audio frames to PCM16. */
object PcmuDecoder {
    private val decodeTable = IntArray(256) { i ->
        val mu = 255 - i
        val sign = if (mu and 0x80 != 0) -1 else 1
        val exponent = (mu shr 4) and 0x07
        val mantissa = mu and 0x0F
        val sample = ((mantissa shl 1) + 33) shl (exponent + 2)
        sign * (sample - 132)
    }

    fun decode(pcmu: ByteArray): ByteArray {
        val pcm = ByteArray(pcmu.size * 2)
        for (i in pcmu.indices) {
            val sample = decodeTable[pcmu[i].toInt() and 0xFF].coerceIn(-32768, 32767)
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = (sample shr 8).toByte()
        }
        return pcm
    }
}
