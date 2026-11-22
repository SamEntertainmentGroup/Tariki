package com.dztrafic.carte

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Loupe d'une seule couleur (cercle et manche), pour le bouton de recherche d'itinéraire. */
class SearchDrawable(tint: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val size = minOf(area.width(), area.height()).toFloat()
        val left = area.exactCenterX() - size / 2
        val top = area.exactCenterY() - size / 2
        val radius = size * 0.30f
        val cx = left + size * 0.42f
        val cy = top + size * 0.42f
        paint.strokeWidth = size * 0.11f
        canvas.drawCircle(cx, cy, radius, paint)
        val start = radius * 0.7071f + paint.strokeWidth * 0.3f
        paint.strokeWidth = size * 0.14f
        canvas.drawLine(cx + start, cy + start, left + size * 0.90f, top + size * 0.90f, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
