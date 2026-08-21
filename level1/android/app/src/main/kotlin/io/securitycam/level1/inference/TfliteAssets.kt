package io.securitycam.level1.inference

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Loads bundled `.tflite` models from assets into direct native-order buffers. */
object TfliteAssets {
    fun loadModelFile(context: Context, path: String): ByteBuffer {
        val fd = context.assets.openFd(path)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                .order(ByteOrder.nativeOrder())
        }
    }
}