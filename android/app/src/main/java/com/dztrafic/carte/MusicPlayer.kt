package com.dztrafic.carte

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lecteur de la musique téléchargée. Il continue écran éteint tant que l'appli tourne
 * et enchaîne les morceaux téléchargés du même genre.
 */
object MusicPlayer {

    interface Listener {
        fun onPlayerChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var player: MediaPlayer? = null
    private var queue: List<AlertsApi.Track> = emptyList()
    private var appContext: Context? = null
    private var focusRequest: AudioFocusRequest? = null

    var current: AlertsApi.Track? = null
        private set

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
        }
    }

    fun add(listener: Listener) {
        listeners.add(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    /** Joue [track] ; la suite enchaîne les morceaux téléchargés de [playlist]. */
    fun play(context: Context, track: AlertsApi.Track, playlist: List<AlertsApi.Track>) {
        appContext = context.applicationContext
        queue = playlist.filter { MusicLibrary.isDownloaded(it) }
        start(track)
    }

    fun toggle() {
        val active = player ?: return
        if (active.isPlaying) {
            pause()
        } else {
            requestFocus()
            active.start()
            notifyChanged()
        }
    }

    /** Position de lecture en millisecondes. */
    val position: Int
        get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** Durée du morceau en millisecondes (0 si inconnue). */
    val duration: Int
        get() = runCatching { player?.duration?.coerceAtLeast(0) ?: 0 }.getOrDefault(0)

    fun seekTo(milliseconds: Int) {
        runCatching { player?.seekTo(milliseconds) }
    }

    fun next() = move(1)

    fun previous() = move(-1)

    fun stop() {
        player?.release()
        player = null
        current = null
        abandonFocus()
        notifyChanged()
    }

    private fun pause() {
        runCatching { player?.pause() }
        notifyChanged()
    }

    private fun move(step: Int) {
        val playing = current ?: return
        val index = queue.indexOfFirst { it.id == playing.id }
        val target = queue.getOrNull(index + step)
        if (target == null) stop() else start(target)
    }

    private fun start(track: AlertsApi.Track) {
        val context = appContext ?: return
        player?.release()
        val media = MediaPlayer()
        val ok = runCatching {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            media.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            media.setDataSource(MusicLibrary.file(track).path)
            media.setOnCompletionListener { next() }
            media.prepare()
        }.isSuccess
        if (!ok) {
            media.release()
            player = null
            current = null
            notifyChanged()
            return
        }
        player = media
        current = track
        requestFocus()
        media.start()
        notifyChanged()
    }

    private fun requestFocus() {
        val manager = appContext?.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        val manager = appContext?.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
    }

    private fun notifyChanged() {
        listeners.forEach { it.onPlayerChanged() }
    }
}
