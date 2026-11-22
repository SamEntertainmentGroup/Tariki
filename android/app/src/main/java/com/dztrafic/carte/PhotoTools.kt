package com.dztrafic.carte

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Photo de signalement : remise à l'endroit, réduite à 800 px et compressée (environ 100 Ko). */
object PhotoTools {

    private const val MAX_SIDE = 800
    private const val JPEG_QUALITY = 78

    /** Prépare [source] dans [target] ; renvoie false si la photo est illisible. */
    fun prepare(source: File, target: File): Boolean = runCatching {
        val bitmap = decodeSampled(source, MAX_SIDE) ?: return false
        val rotated = rotateFromExif(source, bitmap)
        val scale = MAX_SIDE.toFloat() / max(rotated.width, rotated.height)
        val output = if (scale < 1f) {
            Bitmap.createScaledBitmap(rotated, (rotated.width * scale).roundToInt(), (rotated.height * scale).roundToInt(), true)
        } else {
            rotated
        }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        true
    }.getOrDefault(false)

    /** Lit une image en mémoire réduite : le côté le plus grand reste au moins [side] pixels. */
    fun decodeSampled(file: File, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun rotateFromExif(file: File, bitmap: Bitmap): Bitmap {
        val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
