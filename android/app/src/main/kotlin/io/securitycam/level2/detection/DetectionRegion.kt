package io.securitycam.level2.detection

object DetectionRegionShape {
    const val rect = "rect"
    const val poly = "poly"
    const val tripwire = "tripwire"
    val values = listOf(rect, poly)
}

/**
 * Inclusion region in normalized analysis-frame space (0..1, flattened
 * [x0,y0,x1,y1] for rects, [x0,y0,x1,y1,...] vertex pairs for polys).
 * Empty regions = detect everywhere. Port of `lib/core/models.dart`.
 */
data class DetectionRegion(
    val id: String,
    val shape: String,
    val label: String,
    val points: List<Double>,
    val direction: String = "either",
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id,
        "shape" to shape,
        "label" to label,
        "points" to points,
        "direction" to direction,
    )

    companion object {
        fun fromJson(json: Map<String, Any?>): DetectionRegion = DetectionRegion(
            id = json["id"] as String,
            shape = json["shape"] as String,
            label = json["label"] as String,
            points = (json["points"] as? List<*>)
                ?.map { (it as Number).toDouble() }
                ?: emptyList(),
            direction = json["direction"] as? String ?: "either",
        )
    }
}