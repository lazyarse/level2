package io.securitycam.level2.camera_service

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

interface LiveViewEncoderCallback {
    fun onVideoData(nalUnit: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean)
    fun onVideoConfig(sps: ByteArray, pps: ByteArray)
    fun onAudioData(data: ByteArray, presentationTimeUs: Long)
}

class LiveViewEncoder(private val callback: LiveViewEncoderCallback) {

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var videoRunning = false
    private var audioRunning = false

    private var width = 0
    private var height = 0
    private var fps = 0
    private var bitrate = DEFAULT_BITRATE
    private var audioEnabled = false

    fun configure(width: Int, height: Int, fps: Int, bitrate: Int, audioEnabled: Boolean) {
        this.width = width
        this.height = height
        this.fps = fps
        this.bitrate = bitrate
        this.audioEnabled = audioEnabled

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        if (audioEnabled) {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
                setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
    }

    fun start() {
        videoCodec?.start()
        videoRunning = true
        if (audioEnabled) {
            audioCodec?.start()
            audioRunning = true
        }
    }

    fun requestKeyFrame() {
        try {
            videoCodec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        videoRunning = false
        audioRunning = false

        videoCodec?.let { codec ->
            try {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainVideoCodec(stopDrain = true)
            } catch (_: Exception) {
            } finally {
                try {
                    codec.stop()
                    codec.release()
                } catch (_: Exception) {
                }
            }
        }
        videoCodec = null

        audioCodec?.let { codec ->
            try {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainAudioCodec(stopDrain = true)
            } catch (_: Exception) {
            } finally {
                try {
                    codec.stop()
                    codec.release()
                } catch (_: Exception) {
                }
            }
        }
        audioCodec = null
    }

    fun feedVideoFrame(image: Image, timestampUs: Long) {
        val codec = videoCodec ?: return
        if (!videoRunning) return

        val nv12Data = yuv420ToNv12(image)
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(nv12Data)
            codec.queueInputBuffer(inputIndex, 0, nv12Data.size, timestampUs, 0)
        }

        drainVideoCodec(stopDrain = false)
    }

    fun feedAudioData(data: ByteArray, timestampUs: Long) {
        val codec = audioCodec ?: return
        if (!audioRunning) return

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, timestampUs, 0)
        }

        drainAudioCodec(stopDrain = false)
    }

    private fun drainVideoCodec(stopDrain: Boolean) {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (stopDrain) 10_000 else 0)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            }
            if (outputIndex < 0) {
                break
            }

            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
            val outputData = ByteArray(bufferInfo.size)
            outputBuffer.get(outputData)

            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

            if (isConfig) {
                val nalUnits = extractNalUnits(outputData)
                for (nal in nalUnits) {
                    if (nal.size < 1) continue
                    val naluType = nal[0].toInt() and 0x1F
                    when (naluType) {
                        7 -> callback.onVideoConfig(sps = nal, pps = ByteArray(0))
                        8 -> {
                            val currentConfig = nalUnits.firstOrNull { (it[0].toInt() and 0x1F) == 7 }
                            if (currentConfig != null) {
                                callback.onVideoConfig(sps = currentConfig, pps = nal)
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                continue
            }

            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val nalUnits = extractNalUnits(outputData)
            for (nal in nalUnits) {
                if (nal.isEmpty()) continue
                callback.onVideoData(nal, bufferInfo.presentationTimeUs, isKeyFrame)
            }

            codec.releaseOutputBuffer(outputIndex, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
    }

    private fun drainAudioCodec(stopDrain: Boolean) {
        val codec = audioCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (stopDrain) 10_000 else 0)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            }
            if (outputIndex < 0) {
                break
            }

            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: break
            val outputData = ByteArray(bufferInfo.size)
            outputBuffer.get(outputData)

            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isConfig && bufferInfo.size > 0) {
                callback.onAudioData(outputData, bufferInfo.presentationTimeUs)
            }

            codec.releaseOutputBuffer(outputIndex, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
    }

    private fun extractNalUnits(buffer: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var i = 0
        var nalStart = -1

        while (i < buffer.size - 3) {
            val is4Byte = buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() &&
                    buffer[i + 2] == 0.toByte() && buffer[i + 3] == 1.toByte()
            val is3Byte = !is4Byte && buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() &&
                    buffer[i + 2] == 1.toByte()

            if (is4Byte || is3Byte) {
                if (nalStart >= 0) {
                    nalUnits.add(buffer.copyOfRange(nalStart, i))
                }
                nalStart = i + if (is4Byte) 4 else 3
                i = nalStart
            } else {
                i++
            }
        }

        if (nalStart >= 0 && nalStart < buffer.size) {
            nalUnits.add(buffer.copyOfRange(nalStart, buffer.size))
        }

        return nalUnits
    }

    private fun yuv420ToNv12(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv12Data = ByteArray(width * height * 3 / 2)

        var pos = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIndex = row * yRowStride + col
                if (yIndex < yBuffer.remaining()) {
                    nv12Data[pos++] = yBuffer.get(yIndex)
                } else {
                    nv12Data[pos++] = 0
                }
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                val u = if (uvIndex < uBuffer.remaining()) uBuffer.get(uvIndex) else 0
                val v = if (uvIndex < vBuffer.remaining()) vBuffer.get(uvIndex) else 0
                nv12Data[pos++] = u
                nv12Data[pos++] = v
            }
        }

        return nv12Data
    }

    companion object {
        const val DEFAULT_BITRATE = 2_000_000
        const val I_FRAME_INTERVAL = 2
    }
}
