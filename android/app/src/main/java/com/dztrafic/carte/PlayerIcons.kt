package com.dztrafic.carte

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Icônes du lecteur de musique, dessinées pour rester nettes et entières à toutes les tailles. */
class PlayerIcon(private val shape: Shape, tint: Int) : MenuIcon(tint) {

    enum class Shape { PLAY, PAUSE, NEXT, PREVIOUS, STOP, CLOSE }

    private val path = Path()

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.FILL
        path.reset()
        when (shape) {
            Shape.PLAY -> triangle(left + size * 0.26f, top, size, size * 0.56f)
            Shape.PAUSE -> {
                val bar = size * 0.2f
                canvas.drawRoundRect(RectF(left + size * 0.22f, top + size * 0.18f, left + size * 0.22f + bar, top + size * 0.82f), bar / 4, bar / 4, paint)
                canvas.drawRoundRect(RectF(left + size * 0.58f, top + size * 0.18f, left + size * 0.58f + bar, top + size * 0.82f), bar / 4, bar / 4, paint)
            }
            Shape.NEXT -> {
                triangle(left + size * 0.14f, top, size, size * 0.5f)
                canvas.drawRect(left + size * 0.70f, top + size * 0.22f, left + size * 0.84f, top + size * 0.78f, paint)
            }
            Shape.PREVIOUS -> {
                path.moveTo(left + size * 0.86f, top + size * 0.22f)
                path.lineTo(left + size * 0.86f, top + size * 0.78f)
                path.lineTo(left + size * 0.36f, top + size * 0.5f)
                path.close()
                canvas.drawRect(left + size * 0.16f, top + size * 0.22f, left + size * 0.30f, top + size * 0.78f, paint)
            }
            Shape.STOP -> canvas.drawRoundRect(
                RectF(left + size * 0.24f, top + size * 0.24f, left + size * 0.76f, top + size * 0.76f), size * 0.06f, size * 0.06f, paint,
            )
            Shape.CLOSE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.11f
                canvas.drawLine(left + size * 0.26f, top + size * 0.26f, left + size * 0.74f, top + size * 0.74f, paint)
                canvas.drawLine(left + size * 0.74f, top + size * 0.26f, left + size * 0.26f, top + size * 0.74f, paint)
            }
        }
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }

    /** Triangle pointé vers la droite, de largeur [width], qui commence à [start]. */
    private fun triangle(start: Float, top: Float, size: Float, width: Float) {
        path.moveTo(start, top + size * 0.2f)
        path.lineTo(start + width, top + size * 0.5f)
        path.lineTo(start, top + size * 0.8f)
        path.close()
    }
}
