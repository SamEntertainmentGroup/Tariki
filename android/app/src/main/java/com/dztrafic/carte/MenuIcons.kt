package com.dztrafic.carte

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Icône d'une seule couleur dessinée dans un carré centré, comme la loupe du menu. */
abstract class MenuIcon(tint: Int) : Drawable() {

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    final override fun draw(canvas: Canvas) {
        val area = bounds
        val size = minOf(area.width(), area.height()).toFloat()
        drawIcon(canvas, area.exactCenterX() - size / 2, area.exactCenterY() - size / 2, size)
    }

    protected abstract fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Bulle de discussion avec une petite pointe en bas à gauche. */
class ChatDrawable(tint: Int) : MenuIcon(tint) {

    private val bubble = Path()

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.10f
        val body = RectF(left + size * 0.10f, top + size * 0.14f, left + size * 0.90f, top + size * 0.70f)
        val corner = size * 0.18f
        bubble.reset()
        bubble.addRoundRect(body, corner, corner, Path.Direction.CW)
        canvas.drawPath(bubble, paint)
        bubble.reset()
        bubble.moveTo(left + size * 0.30f, body.bottom)
        bubble.lineTo(left + size * 0.22f, top + size * 0.90f)
        bubble.lineTo(left + size * 0.48f, body.bottom)
        canvas.drawPath(bubble, paint)
    }
}

/** Deux notes de musique reliées par une barre. */
class MusicDrawable(tint: Int) : MenuIcon(tint) {

    private val beam = Path()

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        val headW = size * 0.26f
        val headH = size * 0.19f
        val stem = size * 0.09f
        paint.style = Paint.Style.FILL
        canvas.drawOval(RectF(left + size * 0.08f, top + size * 0.66f, left + size * 0.08f + headW, top + size * 0.66f + headH), paint)
        canvas.drawOval(RectF(left + size * 0.58f, top + size * 0.56f, left + size * 0.58f + headW, top + size * 0.56f + headH), paint)
        val leftStem = left + size * 0.08f + headW - stem / 2
        val rightStem = left + size * 0.58f + headW - stem / 2
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = stem
        canvas.drawLine(leftStem, top + size * 0.74f, leftStem, top + size * 0.24f, paint)
        canvas.drawLine(rightStem, top + size * 0.64f, rightStem, top + size * 0.14f, paint)
        paint.style = Paint.Style.FILL
        beam.reset()
        beam.moveTo(leftStem - stem / 2, top + size * 0.20f)
        beam.lineTo(rightStem + stem / 2, top + size * 0.08f)
        beam.lineTo(rightStem + stem / 2, top + size * 0.24f)
        beam.lineTo(leftStem - stem / 2, top + size * 0.36f)
        beam.close()
        canvas.drawPath(beam, paint)
    }
}

/** Silhouette d'une personne : tête ronde et épaules. */
class PersonDrawable(tint: Int) : MenuIcon(tint) {

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(left + size / 2, top + size * 0.30f, size * 0.20f, paint)
        canvas.drawRoundRect(
            RectF(left + size * 0.16f, top + size * 0.56f, left + size * 0.84f, top + size * 0.98f),
            size * 0.24f, size * 0.24f, paint,
        )
    }
}

/** Appareil photo : boîtier arrondi, petit relief en haut et objectif rond. */
class CameraDrawable(tint: Int) : MenuIcon(tint) {

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        val body = RectF(left + size * 0.08f, top + size * 0.28f, left + size * 0.92f, top + size * 0.84f)
        canvas.drawRoundRect(body, size * 0.12f, size * 0.12f, paint)
        canvas.drawCircle(left + size * 0.5f, top + size * 0.56f, size * 0.15f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            RectF(left + size * 0.34f, top + size * 0.14f, left + size * 0.66f, top + size * 0.30f),
            size * 0.05f, size * 0.05f, paint,
        )
    }
}

/** Symbole Bluetooth : trait vertical et deux pointes à droite. */
class BluetoothDrawable(tint: Int) : MenuIcon(tint) {

    private val rune = Path()

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.12f
        rune.reset()
        rune.moveTo(left + size * 0.26f, top + size * 0.30f)
        rune.lineTo(left + size * 0.74f, top + size * 0.70f)
        rune.lineTo(left + size * 0.50f, top + size * 0.92f)
        rune.lineTo(left + size * 0.50f, top + size * 0.08f)
        rune.lineTo(left + size * 0.74f, top + size * 0.30f)
        rune.lineTo(left + size * 0.26f, top + size * 0.70f)
        canvas.drawPath(rune, paint)
    }
}

/** Carte pliée en trois volets. */
class FoldedMapDrawable(tint: Int) : MenuIcon(tint) {

    private val outline = Path()

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        val x = floatArrayOf(0.10f, 0.37f, 0.63f, 0.90f).map { left + size * it }
        val high = top + size * 0.16f
        val low = top + size * 0.26f
        val bottomHigh = top + size * 0.74f
        val bottomLow = top + size * 0.84f
        outline.reset()
        outline.moveTo(x[0], low)
        outline.lineTo(x[1], high)
        outline.lineTo(x[2], low)
        outline.lineTo(x[3], high)
        outline.lineTo(x[3], bottomHigh)
        outline.lineTo(x[2], bottomLow)
        outline.lineTo(x[1], bottomHigh)
        outline.lineTo(x[0], bottomLow)
        outline.close()
        canvas.drawPath(outline, paint)
        canvas.drawLine(x[1], high, x[1], bottomHigh, paint)
        canvas.drawLine(x[2], low, x[2], bottomLow, paint)
    }
}

/** Triangle d'avertissement avec un point d'exclamation. */
class WarningDrawable(tint: Int) : MenuIcon(tint) {

    private val triangle = Path()

    override fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        triangle.reset()
        triangle.moveTo(left + size * 0.5f, top + size * 0.12f)
        triangle.lineTo(left + size * 0.92f, top + size * 0.86f)
        triangle.lineTo(left + size * 0.08f, top + size * 0.86f)
        triangle.close()
        canvas.drawPath(triangle, paint)
        canvas.drawLine(left + size * 0.5f, top + size * 0.40f, left + size * 0.5f, top + size * 0.60f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(left + size * 0.5f, top + size * 0.72f, size * 0.055f, paint)
    }
}
