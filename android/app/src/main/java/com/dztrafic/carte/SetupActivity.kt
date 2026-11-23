package com.dztrafic.carte

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * Premier écran de l'appli, fixe, sans défilement. Si la carte, le fond et les itinéraires sont déjà sur le téléphone,
 * il ouvre directement la carte ; sinon il les télécharge une fois (environ 150 Mo), avec reprise si la connexion coupe.
 */
class SetupActivity : Activity(), OfflineData.Listener {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private val arabic = Locale.getDefault().language == "ar"

    private fun text(ar: String, fr: String) = if (arabic) ar else fr

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initArabicUi(arabic)
        if (OfflineData.isReady(this)) {
            openMap()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        OfflineData.listener = this
        if (OfflineData.running) showRunning() else showWaiting(null)
    }

    override fun onDestroy() {
        if (OfflineData.listener === this) OfflineData.listener = null
        super.onDestroy()
    }

    private fun openMap() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun buildLayout(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(246, 244, 239))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        page.addView(noticeText("طريقي", 34f, ORANGE, bold = true))
        page.addView(noticeText("لكي يعمل التطبيق بدون إنترنت، يجب تحميل خريطة الجزائر وتونس مرة واحدة", 17f, TEXT_DARK, top = 44))
        page.addView(noticeText("Pour fonctionner sans internet, Tariki télécharge une fois la carte de l'Algérie et de la Tunisie", 15f, TEXT_SOFT, top = 6))
        // Ce que fait l'appli, comme dans l'aide, dans la langue du téléphone.
        helpEntries(arabic).forEachIndexed { index, entry ->
            val title = noticeText(entry.title, 16f, TEXT_DARK, bold = true)
            // L'icône se place au début du titre : à gauche en français, à droite en arabe.
            page.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, dp(if (index == 0) 30 else 14), 0, 0)
                val icon = entry.icon?.let { draw ->
                    ImageView(this@SetupActivity).apply { setImageDrawable(draw(BUTTON_DARK)) }
                }
                val iconParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(dp(8), 0, dp(8), 0) }
                if (icon != null && !arabic) addView(icon, iconParams)
                addView(title)
                if (icon != null && arabic) addView(icon, iconParams)
            })
            page.addView(noticeText(entry.text, 14f, TEXT_SOFT, top = 2))
        }
        startButton = Button(this).apply {
            isAllCaps = false
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(ORANGE, 16)
            setOnClickListener { begin() }
        }
        page.addView(startButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply {
            topMargin = dp(26)
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        page.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply {
            topMargin = dp(20)
        })
        status = noticeText("", 15f, TEXT_DARK, bold = true, top = 10)
        page.addView(status)
        detail = noticeText("", 13f, TEXT_SOFT, top = 4)
        page.addView(detail)
        return page
    }

    private fun showLine(view: TextView, value: String) {
        view.text = value
        view.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun noticeText(value: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(top), 0, 0)
    }

    private fun begin() {
        if (!isOnline()) {
            showWaiting(text("لا يوجد إنترنت", "Pas d'internet"))
            return
        }
        OfflineData.start(this)
        showRunning()
    }

    /** [problem] : message court en cas d'échec ; [reason] : cause technique, affichée en petit. */
    private fun showWaiting(problem: String?, reason: String = "") {
        progress.visibility = View.GONE
        startButton.visibility = View.VISIBLE
        startButton.text = if (problem == null) text("تحميل", "Télécharger") else text("إعادة المحاولة", "Réessayer")
        showLine(status, problem?.let { "⚠️ $it" }.orEmpty())
        showLine(detail, if (reason.isEmpty()) "" else reason + "\n" + text(
            "سيُستأنف التحميل من حيث توقف",
            "Le téléchargement reprendra là où il s'est arrêté",
        ))
    }

    private fun showRunning() {
        startButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        showLine(status, text("جارٍ التحميل…", "Téléchargement…"))
        showLine(detail, "")
    }

    override fun onProgress(part: OfflineData.Part, doneBytes: Long, totalBytes: Long) {
        val index = OfflineData.Part.entries.indexOf(part) + 1
        val count = OfflineData.Part.entries.size
        showLine(status, "$index / $count · " + text(part.labelAr, part.labelFr))
        val doneMb = doneBytes / 1_000_000.0
        val unit = text("ميغابايت", "Mo")
        if (totalBytes > 0) {
            progress.isIndeterminate = false
            progress.progress = (doneBytes * 1000 / totalBytes).toInt()
            showLine(detail, String.format(Locale.US, "%.0f / %.0f %s", doneMb, totalBytes / 1_000_000.0, unit))
        } else {
            progress.isIndeterminate = true
            showLine(detail, String.format(Locale.US, "%.0f %s", doneMb, unit))
        }
    }

    override fun onFinished(error: String?) {
        if (error == null && OfflineData.isReady(this)) {
            openMap()
        } else {
            showWaiting(text("تعذّر التحميل", "Téléchargement impossible"), error.orEmpty())
        }
    }
}
