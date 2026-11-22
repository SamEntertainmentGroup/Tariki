package com.dztrafic.carte

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Musique hors ligne : la liste vient du site (gardée pour être consultée sans internet), chaque morceau
 * se télécharge une fois puis s'écoute sans connexion.
 */
object MusicLibrary {

    interface Listener {
        fun onMusicChanged()
    }

    private const val KEY_TRACKS = "music_tracks"
    private const val TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_EVERY_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private val downloads = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var appContext: Context? = null

    /** Pourcentage des téléchargements en cours (−1 : en attente). */
    val progress = ConcurrentHashMap<Long, Int>()

    var tracks: List<AlertsApi.Track> = emptyList()
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        tracks = readCache(context.applicationContext)
    }

    fun add(listener: Listener) {
        listeners.add(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun genres(): List<String> = tracks.map { it.genre }.distinct().sorted()

    fun tracksOf(genre: String): List<AlertsApi.Track> = tracks.filter { it.genre == genre }

    fun file(track: AlertsApi.Track): File {
        val context = appContext ?: throw IllegalStateException("MusicLibrary.init manquant")
        val extension = track.url.substringAfterLast('.', "mp3").substringBefore('?').lowercase()
            .takeIf { it in setOf("mp3", "m4a", "aac", "ogg") } ?: "mp3"
        return File(File(context.filesDir, "music").apply { mkdirs() }, "${track.id}.$extension")
    }

    fun isDownloaded(track: AlertsApi.Track): Boolean = file(track).exists()

    /** Met à jour la liste depuis le site si internet est disponible. */
    fun refresh() {
        val context = appContext ?: return
        val client = AlertsRepository.apiClient ?: return
        if (!context.isOnline()) return
        network.execute {
            val fresh = runCatching { client.fetchMusic() }.getOrNull() ?: return@execute
            writeCache(context, fresh)
            main.post {
                tracks = fresh
                notifyChanged()
            }
        }
    }

    fun download(track: AlertsApi.Track) {
        if (isDownloaded(track) || progress.containsKey(track.id)) return
        progress[track.id] = -1
        notifyChanged()
        downloads.execute {
            val ok = runCatching { fetch(track) }.isSuccess
            progress.remove(track.id)
            main.post {
                if (!ok) TrackerHub.notifyMessage("❌ تعذّر تحميل الأغنية · Téléchargement impossible")
                notifyChanged()
            }
        }
    }

    fun delete(track: AlertsApi.Track) {
        if (MusicPlayer.current?.id == track.id) MusicPlayer.stop()
        file(track).delete()
        notifyChanged()
    }

    private fun fetch(track: AlertsApi.Track) {
        val target = file(track)
        val partial = File(target.path + ".part")
        val connection = URL(track.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("code ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastReport > PROGRESS_EVERY_MS) {
                            lastReport = now
                            progress[track.id] = (done * 100 / total).toInt()
                            main.post { notifyChanged() }
                        }
                    }
                    if (total > 0 && done < total) throw IOException("connexion coupée")
                }
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        if (!partial.renameTo(target)) throw IOException("enregistrement impossible")
    }

    private fun notifyChanged() {
        listeners.forEach { it.onMusicChanged() }
    }

    private fun readCache(context: Context): List<AlertsApi.Track> {
        val text = context.getSharedPreferences("dz_store", Context.MODE_PRIVATE).getString(KEY_TRACKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AlertsApi.Track(item.getLong("id"), item.getString("genre"), item.getString("title"), item.getString("url"), item.optLong("size"))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCache(context: Context, list: List<AlertsApi.Track>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("genre", it.genre).put("title", it.title).put("url", it.url).put("size", it.size))
        }
        context.getSharedPreferences("dz_store", Context.MODE_PRIVATE).edit().putString(KEY_TRACKS, array.toString()).apply()
    }
}
