package com.dztrafic.carte

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Données hors ligne téléchargées une seule fois au premier lancement : carte, fond (mer et pays voisins)
 * et itinéraires. Elles ne sont pas dans l'APK et ne peuvent pas être supprimées depuis l'appli.
 * Un téléchargement coupé reprend là où il s'était arrêté.
 */
object OfflineData {

    enum class Part(val fileName: String, val gzip: Boolean, val labelAr: String, val labelFr: String) {
        MAP("algeria-map.mbtiles", false, "الخريطة", "Carte"),
        BASE("algeria-base.geojson", true, "البحر والدول المجاورة", "Mer et pays voisins"),
        ROUTING("algeria-routing.bin", true, "المسارات", "Itinéraires"),
        ;

        val url: String
            get() = when (this) {
                MAP -> BuildConfig.MAP_URL
                BASE -> BuildConfig.BASE_URL
                ROUTING -> BuildConfig.ROUTING_URL
            }
    }

    interface Listener {
        fun onProgress(part: Part, doneBytes: Long, totalBytes: Long)
        fun onFinished(error: String?)
    }

    /**
     * Version des données attendue par l'appli. Des fichiers d'une autre version (anciens essais, format
     * changé, pays ajouté ou fond de carte corrigé) sont effacés et téléchargés de nouveau.
     * 2 : ajout de la Tunisie. 3 : terres de la frontière algéro-tunisienne corrigées.
     */
    private const val DATA_VERSION = 3
    private const val KEY_DATA_VERSION = "offline_data_version"
    private const val BUFFER_BYTES = 64 * 1024
    private const val TIMEOUT_MS = 30_000
    private const val PROGRESS_EVERY_MS = 300L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var running = false
        private set

    var listener: Listener? = null

    fun file(context: Context, part: Part) = File(context.filesDir, part.fileName)

    fun isReady(context: Context): Boolean = storedVersion(context) == DATA_VERSION &&
        Part.entries.all { file(context, it).let { f -> f.exists() && f.length() > 0 } }

    /** Fichier abîmé : il sera téléchargé de nouveau au prochain lancement. */
    fun discard(context: Context, part: Part) {
        file(context, part).delete()
    }

    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        Thread {
            val error = try {
                // Anciens fichiers effacés une seule fois : après une coupure, les parties déjà reçues sont gardées.
                if (storedVersion(app) != DATA_VERSION) {
                    Part.entries.forEach { file(app, it).delete() }
                    prefs(app).edit().putInt(KEY_DATA_VERSION, DATA_VERSION).commit()
                }
                Part.entries.filter { !file(app, it).exists() }.forEach { download(app, it) }
                null
            } catch (exception: Exception) {
                exception.message ?: exception.javaClass.simpleName
            }
            main.post {
                running = false
                listener?.onFinished(error)
            }
        }.start()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("dz_store", Context.MODE_PRIVATE)

    private fun storedVersion(context: Context): Int = prefs(context).getInt(KEY_DATA_VERSION, 0)

    private fun download(context: Context, part: Part) {
        val partial = File(context.filesDir, part.fileName + ".part")
        fetch(part, partial)
        val ready = File(context.filesDir, part.fileName + ".ready")
        // Un fichier reçu mais illisible est jeté en entier : le prochain essai repart de zéro.
        val valid = runCatching {
            if (part.gzip) {
                GZIPInputStream(partial.inputStream().buffered(BUFFER_BYTES), BUFFER_BYTES).use { input ->
                    FileOutputStream(ready).use { output -> input.copyTo(output, BUFFER_BYTES) }
                }
            } else if (!partial.renameTo(ready)) {
                throw IOException("enregistrement impossible")
            }
            looksValid(part, ready)
        }.getOrDefault(false)
        partial.delete()
        if (!valid) {
            ready.delete()
            throw IOException("fichier incomplet, réessayez")
        }
        if (!ready.renameTo(file(context, part))) throw IOException("enregistrement impossible")
    }

    /** Télécharge dans [partial] en reprenant à sa taille actuelle si le serveur l'accepte. */
    private fun fetch(part: Part, partial: File) {
        var offset = if (partial.exists()) partial.length() else 0L
        val connection = URL(part.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> offset = 0
                416 -> {
                    // Déjà entièrement reçu lors d'un essai précédent.
                    if (offset > 0) return
                    throw IOException("serveur : code $code")
                }
                else -> throw IOException("serveur : code $code")
            }
            val length = connection.contentLengthLong
            val total = if (length > 0) offset + length else -1L
            RandomAccessFile(partial, "rw").use { output ->
                output.setLength(offset)
                output.seek(offset)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = offset
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > PROGRESS_EVERY_MS) {
                            lastReport = now
                            val reported = done
                            main.post { listener?.onProgress(part, reported, total) }
                        }
                    }
                    if (total > 0 && done < total) throw IOException("connexion coupée")
                    main.post { listener?.onProgress(part, done, total) }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun looksValid(part: Part, file: File): Boolean {
        if (file.length() < 16) return false
        val head = ByteArray(16)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        return when (part) {
            Part.MAP -> String(head, Charsets.US_ASCII).startsWith("SQLite format 3")
            Part.BASE -> head[0] == '{'.code.toByte()
            Part.ROUTING -> String(head, 0, 4, Charsets.US_ASCII) == "TRK1"
        }
    }
}
