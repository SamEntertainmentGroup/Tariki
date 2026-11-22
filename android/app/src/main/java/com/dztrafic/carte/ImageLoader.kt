package com.dztrafic.carte

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Photos des signalements : téléchargées une fois puis gardées en cache sur le téléphone,
 * pour rester visibles hors ligne. Sans connexion et sans cache, l'image reste vide.
 */
object ImageLoader {

    private const val TIMEOUT_MS = 15_000
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, url: String, view: ImageView, sidePx: Int) {
        view.tag = url
        val app = context.applicationContext
        executor.execute {
            val bitmap = runCatching { bitmapFor(app, url, sidePx) }.getOrNull()
            main.post { if (view.tag == url && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun bitmapFor(context: Context, url: String, sidePx: Int): Bitmap? {
        val cached = cacheFile(context, url)
        if (!cached.exists()) {
            val partial = File(cached.path + ".part")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (!partial.renameTo(cached)) return null
        }
        return PhotoTools.decodeSampled(cached, sidePx)
    }

    private fun cacheFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "photos-alertes").apply { mkdirs() }, "$name.jpg")
    }
}
