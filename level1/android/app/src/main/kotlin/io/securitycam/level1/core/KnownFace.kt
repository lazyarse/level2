package io.securitycam.level1.core

/**
 * An enrolled person: stable id (matches the centroid bin in
 * `filesDir/known_faces/`) plus a display label.
 */
data class KnownFace(
    val id: String,
    val label: String,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id,
        "label" to label,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): KnownFace = KnownFace(
            id = json["id"] as? String ?: "",
            label = json["label"] as? String ?: "",
        )
    }
}
