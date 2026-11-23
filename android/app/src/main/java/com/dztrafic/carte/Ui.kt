package com.dztrafic.carte

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Couleurs et petites aides d'affichage communes à tous les écrans. */
val TEXT_DARK = Color.rgb(24, 53, 42)
val TEXT_SOFT = Color.rgb(100, 116, 107)
val BUTTON_GREY = Color.rgb(104, 116, 109)
/** Couleur commune des boutons ronds (☰, loupe, chat, musique, aide, cible) et du genre de musique choisi. */
val BUTTON_DARK = Color.rgb(25, 54, 46)
val ROUTE_BLUE = Color.rgb(26, 115, 232)
const val ORANGE = 0xFFFF6D00.toInt()

private const val PREFS = "dz_store"
private const val KEY_LANGUAGE = "language_ar"

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != null) setStroke(dp(2), strokeColor)
}

fun Context.oval(color: Int, strokeColor: Int, strokeDp: Int = 1) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke(dp(strokeDp), strokeColor)
}

/** Langue de l'interface (arabe par défaut), gardée d'un lancement à l'autre. */
fun Context.isArabicUi(): Boolean =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LANGUAGE, true)

fun Context.saveArabicUi(arabic: Boolean) {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LANGUAGE, arabic).apply()
}

/** Au premier lancement, la langue de l'appli suit celle du téléphone ; ensuite c'est le bouton AR/FR qui décide. */
fun Context.initArabicUi(arabic: Boolean) {
    if (!getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_LANGUAGE)) saveArabicUi(arabic)
}

fun Context.isOnline(): Boolean = runCatching {
    val manager = getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(false)

/** Une ligne d'explication : titre, phrase courte, et petite icône dessinée sur la page de téléchargement. */
data class HelpEntry(val title: String, val text: String, val icon: ((Int) -> Drawable)? = null)

/** Ce que fait Tariki, en titres et phrases courtes : montré dans l'aide et sur la page de téléchargement. */
fun helpEntries(arabic: Boolean): List<HelpEntry> = if (arabic) {
    listOf(
        HelpEntry("خريطة بدون إنترنت", "الخريطة والمسار يعملان في كل مكان، حتى بدون شبكة"),
        HelpEntry("الإبلاغ عن خطر", "اضغط على + وسيتم تنبيه السائقين الآخرين عند الاقتراب منه"),
        HelpEntry("دردشة بدون إنترنت", "تحدث مع المسافرين القريبين عبر البلوتوث", ::ChatDrawable),
        HelpEntry("موسيقى بدون إنترنت", "حمّلها مرة واحدة واستمع إليها في كل مكان", ::MusicDrawable),
        HelpEntry("تطبيق مستقل", "طريقي لا يتبع أي جهة حكومية أو رسمية"),
    )
} else {
    listOf(
        HelpEntry("Carte sans internet", "La carte et l'itinéraire marchent partout, même sans réseau"),
        HelpEntry("Signaler un danger", "Appuie sur + et les autres conducteurs sont prévenus en approchant"),
        HelpEntry("Chat sans internet", "Discute avec les voyageurs proches par Bluetooth", ::ChatDrawable),
        HelpEntry("Musique sans internet", "Télécharge une fois et écoute partout", ::MusicDrawable),
        HelpEntry("Application indépendante", "Tariki ne dépend d'aucun organisme gouvernemental ou officiel"),
    )
}
