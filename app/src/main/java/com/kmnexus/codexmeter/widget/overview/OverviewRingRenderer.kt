package com.kmnexus.codexmeter.widget.overview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * 把雙圓環畫成點陣圖（Glance 小工具沒有畫弧線的元件）。
 * 外圈＝主要額度（5 小時）剩餘，內圈＝第二個額度（7 天）剩餘，
 * 外圈上的短刻度＝這個週期的時間還剩多少：弧線超過刻度代表用太慢。
 *
 * 點陣圖會經由 binder 傳給桌面，太大會整個小工具更新失敗，
 * 所以解析度上限 2.5x，6 個圓環加起來仍遠低於 1MB。
 */
object OverviewRingRenderer {
    private const val MAX_SCALE = 2.5f
    private const val OUTER_STROKE_DP = 7f
    private const val INNER_STROKE_DP = 5f
    private const val RING_GAP_DP = 2.5f
    private const val TRACK_COLOR = 0x4D8A8F99 // 半透明灰：淺色、深色背景都看得到
    private const val INNER_ALPHA = 150

    data class Spec(
        val outerFraction: Float?,
        val innerFraction: Float?,
        val timeLeftFraction: Float?,
        val color: Int,
        val tickColor: Int,
    )

    fun render(spec: Spec, sizeDp: Float, density: Float): Bitmap {
        val scale = density.coerceAtMost(MAX_SCALE)
        val px = (sizeDp * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val outerStroke = OUTER_STROKE_DP * scale
        val innerStroke = INNER_STROKE_DP * scale
        val outerInset = outerStroke / 2
        val innerInset = outerStroke + RING_GAP_DP * scale + innerStroke / 2
        val outerRect = RectF(outerInset, outerInset, px - outerInset, px - outerInset)
        val innerRect = RectF(innerInset, innerInset, px - innerInset, px - innerInset)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        drawRing(canvas, outerRect, outerStroke, spec.outerFraction, spec.color, 255, paint)
        drawRing(canvas, innerRect, innerStroke, spec.innerFraction, spec.color, INNER_ALPHA, paint)
        spec.timeLeftFraction?.let { drawTick(canvas, outerRect, outerStroke, it, spec.tickColor, paint) }
        return bitmap
    }

    private fun drawRing(
        canvas: Canvas,
        rect: RectF,
        stroke: Float,
        fraction: Float?,
        color: Int,
        alpha: Int,
        paint: Paint,
    ) {
        paint.strokeWidth = stroke
        paint.color = TRACK_COLOR
        canvas.drawArc(rect, 0f, 360f, false, paint)
        val sweep = (fraction ?: return).coerceIn(0f, 1f) * 360f
        if (sweep <= 0f) return
        paint.color = color
        paint.alpha = alpha
        // 從 12 點鐘方向順時針畫。
        canvas.drawArc(rect, -90f, sweep, false, paint)
    }

    private fun drawTick(canvas: Canvas, rect: RectF, stroke: Float, fraction: Float, color: Int, paint: Paint) {
        val angle = Math.toRadians((-90.0 + fraction.coerceIn(0f, 1f) * 360.0))
        val radius = rect.width() / 2
        val cx = rect.centerX()
        val cy = rect.centerY()
        val inner = radius - stroke * 0.9f
        val outer = radius + stroke * 0.9f
        paint.strokeWidth = stroke * 0.35f
        paint.color = color
        canvas.drawLine(
            cx + (inner * cos(angle)).toFloat(),
            cy + (inner * sin(angle)).toFloat(),
            cx + (outer * cos(angle)).toFloat(),
            cy + (outer * sin(angle)).toFloat(),
            paint,
        )
    }
}
