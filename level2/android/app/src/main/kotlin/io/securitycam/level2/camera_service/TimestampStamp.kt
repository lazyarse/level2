package io.securitycam.level2.camera_service

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Pure renderer for the burned-in clip timestamp: translucent dark rounded
 * rectangle + white text in the configured corner. Frame-position logic is
 * unit-testable without a video pipeline.
 */
object TimestampStamp {

    /** Stamp text for a frame wall-clock instant. */
    fun text(
        wallMs: Long,
        includeCameraName: Boolean,
        cameraName: String,
    ): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()))
        return if (includeCameraName && cameraName.isNotBlank()) {
            "$cameraName  $time"
        } else {
            time
        }
    }

    fun draw(
        canvas: Canvas,
        wallMs: Long,
        position: String,
        width: Int,
        height: Int,
        includeCameraName: Boolean,
        cameraName: String,
    ) {
        val text = text(wallMs, includeCameraName, cameraName)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(30f, height * 0.032f)
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 0f, Color.argb(140, 0, 0, 0))
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(127, 0, 0, 0)
        }
        val bounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val textH = max(bounds.height(), textPaint.textSize.toInt())
        val padX = textH * 0.55f
        val padY = textH * 0.35f
        val margin = max(width * 0.02f, 12f)
        val boxW = bounds.width() + padX * 2
        val boxH = textH + padY * 2
        val left = if (position.endsWith("Left")) margin else width - margin - boxW
        val top = if (position.startsWith("top")) margin else height - margin - boxH
        val rect = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rect, boxH * 0.3f, boxH * 0.3f, bgPaint)
        val baseline = rect.top + padY - bounds.top
        canvas.drawText(text, rect.left + padX - bounds.left, baseline, textPaint)
    }
}
