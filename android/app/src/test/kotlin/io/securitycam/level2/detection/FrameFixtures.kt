package io.securitycam.level2.detection

/** Test fixture: uniform gray frame. */
internal fun buildFrame(width: Int, height: Int, fill: Int): ByteArray {
    val buf = ByteArray(width * height)
    buf.fill(fill.toByte())
    return buf
}

/** Test fixture: frame with a filled rectangle. */
internal fun buildFrameWithRect(
    width: Int,
    height: Int,
    fill: Int,
    rectX: Int,
    rectY: Int,
    rectW: Int,
    rectH: Int,
    rectFill: Int,
): ByteArray {
    val buf = buildFrame(width, height, fill)
    for (y in rectY until rectY + rectH) {
        if (y >= height) break
        for (x in rectX until rectX + rectW) {
            if (x >= width) break
            buf[y * width + x] = rectFill.toByte()
        }
    }
    return buf
}
