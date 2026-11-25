package com.dztrafic.carte

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** Écran de la musique : genres, morceaux à télécharger une fois puis à écouter sans internet. */
class MusicActivity : Activity(), MusicLibrary.Listener, MusicPlayer.Listener {

    private val arabic by lazy { isArabicUi() }
    private var genre: String? = null
    private var shown: List<AlertsApi.Track> = emptyList()

    private lateinit var genresRow: LinearLayout
    private lateinit var empty: TextView
    private lateinit var downloadAll: Button
    private lateinit var playerTitle: TextView
    private lateinit var playButton: ImageView
    private lateinit var playerBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            showPosition()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = row(shown[position])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MusicLibrary.init(this)
        val root = buildLayout()
        setContentView(root)
        fitSystemBars(root)
    }

    override fun onStart() {
        super.onStart()
        MusicLibrary.add(this)
        MusicPlayer.add(this)
        MusicLibrary.refresh()
        onMusicChanged()
        onPlayerChanged()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        MusicLibrary.remove(this)
        MusicPlayer.remove(this)
        super.onStop()
    }

    private fun text(ar: String, fr: String) = if (arabic) ar else fr

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(241, 244, 242))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(18), dp(16), dp(18), dp(10))
            elevation = dp(4).toFloat()
        }
        header.addView(TextView(this).apply {
            text = text("🎵 موسيقى", "🎵 Musique")
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
        })
        header.addView(TextView(this).apply {
            text = text("حمّل الأغنية مرة واحدة ثم استمع إليها بدون إنترنت", "Télécharge une fois, puis écoute sans internet")
            textSize = 13f
            setTextColor(TEXT_SOFT)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })
        genresRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(genresRow)
        })
        root.addView(header)

        downloadAll = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(ROUTE_BLUE, 14)
            text = text("⬇ تحميل كل أغاني هذا النوع", "⬇ Tout télécharger dans ce genre")
            setOnClickListener { shown.forEach { MusicLibrary.download(it) } }
        }
        root.addView(downloadAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            setMargins(dp(14), dp(12), dp(14), dp(4))
        })

        empty = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT_SOFT)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        root.addView(empty)

        root.addView(ListView(this).apply {
            adapter = this@MusicActivity.adapter
            divider = null
            setPadding(dp(10), dp(4), dp(10), dp(4))
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> onTrackTap(shown[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                confirmDelete(shown[position])
                true
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        playerTitle = TextView(this).apply {
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        seekBar = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(ORANGE)
            thumbTintList = ColorStateList.valueOf(ORANGE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = clock(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    MusicPlayer.seekTo(bar.progress)
                }
            })
        }
        elapsed = timeText()
        total = timeText()
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(total.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        playButton = playerButton(PlayerIcon.Shape.PAUSE, big = true) { MusicPlayer.toggle() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(playerButton(PlayerIcon.Shape.PREVIOUS) { MusicPlayer.previous() })
            addView(playButton)
            addView(playerButton(PlayerIcon.Shape.NEXT) { MusicPlayer.next() })
            addView(playerButton(PlayerIcon.Shape.STOP) { MusicPlayer.stop() })
        }
        playerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24, Color.rgb(226, 233, 228))
            elevation = dp(8).toFloat()
            setPadding(dp(8), dp(16), dp(8), dp(20))
            addView(playerTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), 0, dp(12), dp(6))
            })
            addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
            addView(times)
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        root.addView(playerBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), dp(4), dp(8), dp(8))
        })
        return root
    }

    private fun timeText() = TextView(this).apply {
        textSize = 13f
        setTextColor(TEXT_SOFT)
    }

    /** Bouton rond du lecteur ; le bouton principal [big] est orange, les autres sont discrets. */
    private fun playerButton(shape: PlayerIcon.Shape, big: Boolean = false, onClick: () -> Unit) = ImageView(this).apply {
        val size = if (big) 72 else 56
        setImageDrawable(PlayerIcon(shape, if (big) Color.WHITE else TEXT_DARK))
        val inset = if (big) 20 else 16
        setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
        background = if (big) oval(ORANGE, ORANGE) else oval(Color.rgb(241, 244, 242), Color.rgb(226, 233, 228))
        if (big) elevation = dp(4).toFloat()
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
    }

    private fun showPosition() {
        if (MusicPlayer.current == null || dragging) return
        val length = MusicPlayer.duration
        seekBar.max = length.coerceAtLeast(1)
        seekBar.progress = MusicPlayer.position
        elapsed.text = clock(MusicPlayer.position)
        total.text = if (length > 0) clock(length) else ""
    }

    override fun onMusicChanged() {
        val genres = MusicLibrary.genres()
        if (genre !in genres) genre = genres.firstOrNull()
        genresRow.removeAllViews()
        genres.forEach { name ->
            val selected = name == genre
            genresRow.addView(Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 14f
                setTextColor(if (selected) Color.WHITE else TEXT_DARK)
                background = if (selected) rounded(BUTTON_DARK, 18) else rounded(Color.rgb(241, 244, 242), 18, Color.rgb(210, 220, 214))
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    genre = name
                    onMusicChanged()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        shown = (genre?.let { MusicLibrary.tracksOf(it) } ?: emptyList()).sortedByDescending { MusicLibrary.isDownloaded(it) }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (isOnline()) {
            text("لا توجد موسيقى بعد", "Pas encore de musique")
        } else {
            text("اتصل بالإنترنت لرؤية قائمة الموسيقى", "Connecte-toi à internet pour voir la liste de musique")
        }
        downloadAll.visibility = if (shown.any { !MusicLibrary.isDownloaded(it) }) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    override fun onPlayerChanged() {
        val playing = MusicPlayer.current
        playerBar.visibility = if (playing == null) View.GONE else View.VISIBLE
        playerTitle.text = playing?.title.orEmpty()
        playButton.setImageDrawable(PlayerIcon(if (MusicPlayer.isPlaying) PlayerIcon.Shape.PAUSE else PlayerIcon.Shape.PLAY, Color.WHITE))
        showPosition()
        adapter.notifyDataSetChanged()
    }

    private fun onTrackTap(track: AlertsApi.Track) {
        when {
            MusicPlayer.current?.id == track.id -> MusicPlayer.toggle()
            MusicLibrary.isDownloaded(track) -> MusicPlayer.play(this, track, shown)
            else -> MusicLibrary.download(track)
        }
    }

    private fun confirmDelete(track: AlertsApi.Track) {
        if (!MusicLibrary.isDownloaded(track)) return
        AlertDialog.Builder(this)
            .setMessage(text("حذف هذه الأغنية من الهاتف؟", "Supprimer ce morceau du téléphone ?"))
            .setPositiveButton(text("حذف", "Supprimer")) { _, _ -> MusicLibrary.delete(track) }
            .setNegativeButton(text("إلغاء", "Annuler"), null)
            .show()
    }

    /** Ligne d'un morceau : orange s'il est sur le téléphone, blanche s'il reste à télécharger. */
    private fun row(track: AlertsApi.Track): View {
        val downloaded = MusicLibrary.isDownloaded(track)
        val percent = MusicLibrary.progress[track.id]
        val playing = MusicPlayer.current?.id == track.id
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = when {
                playing -> rounded(Color.rgb(214, 84, 0), 16)
                downloaded -> rounded(ORANGE, 16)
                else -> rounded(Color.WHITE, 16, Color.rgb(226, 233, 228))
            }
            setPadding(dp(16), dp(10), dp(10), dp(10))
            minimumHeight = dp(64)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = track.title
            textSize = 16f
            if (playing) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (downloaded) Color.WHITE else TEXT_DARK)
        })
        if (!downloaded) {
            texts.addView(TextView(this).apply {
                text = String.format(Locale.US, "%.1f Mo", track.size / 1_000_000.0)
                textSize = 12f
                setTextColor(TEXT_SOFT)
            })
        }
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (downloaded) {
            line.addView(ImageView(this).apply {
                val shape = if (playing && MusicPlayer.isPlaying) PlayerIcon.Shape.PAUSE else PlayerIcon.Shape.PLAY
                setImageDrawable(PlayerIcon(shape, Color.WHITE))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        } else {
            line.addView(TextView(this).apply {
                text = when {
                    percent == null -> "⬇"
                    percent < 0 -> "…"
                    else -> "$percent %"
                }
                textSize = if (percent == null) 20f else 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ROUTE_BLUE)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(56), dp(44)))
        }
        return LinearLayout(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private companion object {
        const val TICK_MS = 500L

        /** Durée lisible : 3:07, ou 1:58:30 pour les morceaux de plus d'une heure. */
        fun clock(milliseconds: Int): String {
            val seconds = milliseconds / 1000
            val hours = seconds / 3600
            val minutes = seconds % 3600 / 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds % 60)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds % 60)
            }
        }
    }
}
