package io.securitycam.level2.core

/** Still-image capture attached to alerts and recorded events. */
data class Snapshot(
    val bytes: ByteArray,
    val mimeType: String,
    val name: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Snapshot) return false
        return bytes.contentEquals(other.bytes) &&
            mimeType == other.mimeType &&
            name == other.name
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }
}