package com.dztrafic.carte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.text.Normalizer
import kotlin.math.roundToInt

private const val STYLE_FILE = "style.json"
private const val BASE_SOURCE = "base"
private const val ALERT_SOURCE = "alerts"
private const val ALERT_LAYER = "alerts"
private const val ALERT_CLUSTER_LAYER = "alerts-cluster"
private const val ROUTE_SOURCE = "route"
private const val GROUP_RADIUS_METERS = 50.0
private const val RECENTER_DELAY_MS = 10_000L
private const val MIN_BEARING_SPEED = 1.5f
private const val CAMERA_ANIM_MS = 900
private const val CLUSTER_TAP_ZOOM_STEP = 2.0
private const val PERMISSION_REQUEST = 1
private const val PHOTO_REQUEST = 2
private const val PHOTO_THUMB_DP = 88
private const val START_LATITUDE = 36.1911
private const val START_LONGITUDE = 5.4137
private const val START_ZOOM = 12.0
private const val MIN_ZOOM = 5.0
private const val TRACKING_ZOOM = 15.0
private const val MAX_GPS_AGE_MS = 300_000L
private const val VOYANT_MS = 5_000L
private const val CONFIRM_MS = 10_000L
private const val POPUP_AUTO_CLOSE_MS = 30_000L
private const val STATUS_TICK_MS = 2_000L
private const val SEARCH_STALE_MS = 20_000L
private const val COMMENT_MAX_LENGTH = 140
private const val ALERT_ICON_DP = 48
private const val TAP_RADIUS_DP = 30
private const val CIRCLE_DP = 84

/** Haut du cercle de gauche numéro [position] : 1 vitesse, 2 voyageurs, 3 itinéraire. */
private fun circleTop(position: Int) = 12 + (position - 1) * (CIRCLE_DP + 10)
private const val ROUND_BUTTON_DP = 56
/** Hauteur du champ de description : trois lignes visibles. */
private const val COMMENT_BOX_DP = 100
private const val CAMERA_DP = 40
private const val ON_ROUTE_METERS = 60.0
private const val ROUTE_LOOKAHEAD_METERS = 3_000.0
private const val ARRIVAL_METERS = 40.0
private const val OFF_ROUTE_RECALC_MS = 20_000L
private const val CONTACT_MAX_LENGTH = 1000
/** Zone où le centre de la carte peut aller : l'Algérie et la Tunisie, pour ne jamais voir le bord du fond de carte. */
private val CAMERA_BOUNDS = LatLngBounds.from(37.5, 12.0, 19.0, -8.7)

private enum class LabelLanguage(val expression: String) {
    ARABIC("""["coalesce",["get","name_ar"],["get","name"],["get","name_fr"]]"""),
    FRENCH("""["coalesce",["get","name_fr"],["get","name"],["get","name_ar"]]"""),
}

private enum class SystemState { OK, NO_GPS, NO_NOTIFICATIONS, NO_BATTERY, SEARCHING }

private class AlertGroup(val latitude: Double, val longitude: Double) {
    val comments = LinkedHashSet<String>()
    val photos = LinkedHashSet<String>()
    var total = 0

    fun add(alert: Alert) {
        total += alert.confirmations.coerceAtLeast(1)
        alert.comments.forEach { if (it.isNotBlank() && comments.size < 5) comments.add(it) }
        alert.photos.forEach { if (photos.size < 6) photos.add(it) }
    }
}

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var speedText: TextView
    private lateinit var confirmTitle: TextView
    private lateinit var gpsBanner: TextView
    private lateinit var voyant: TextView
    private lateinit var confirmBox: LinearLayout
    private var map: MapLibreMap? = null
    private var mapUrl = ""
    private var language = LabelLanguage.ARABIC
    private var pendingConfirmation: Alert? = null
    private var followLocation = true
    private var positioned = false
    private var mapBearing = 0.0
    private var systemState = SystemState.OK
    private var routingGraph: RoutingGraph? = null
    private var routePlaces: List<RoutePlace> = emptyList()
    private var routeShown = false
    private var routeBusy = false
    private var routeLatitudes = DoubleArray(0)
    private var routeLongitudes = DoubleArray(0)
    /** Distance cumulée depuis le départ jusqu'à chaque point du tracé (mètres). */
    private var routeCumulative = DoubleArray(0)
    private var routeSeconds = 0.0
    private var routeIndex = 0
    private var routeDestination: RoutePlace? = null
    /** Moment où la voiture a quitté le tracé (0 : sur le tracé). */
    private var offRouteSince = 0L
    private lateinit var routeCircle: LinearLayout
    private lateinit var routeDistanceText: TextView
    private lateinit var routeTimeText: TextView
    private lateinit var routeCancel: TextView
    private var permissionDialog: AlertDialog? = null
    private var permissionRows: LinearLayout? = null
    private var permissionLater: Button? = null
    private var askedLocation = false
    private var askedNotifications = false
    /** Photo prise pour le signalement en cours : fichier brut de l'appareil photo, puis photo préparée. */
    private var photoCapture: File? = null
    private var photoReady: ((File?) -> Unit)? = null
    private lateinit var extraButtons: LinearLayout
    private lateinit var menuDot: View
    private lateinit var menuButton: Button
    private lateinit var travellersCircle: LinearLayout
    private lateinit var travellersText: TextView
    private lateinit var travellersBluetooth: ImageView
    private lateinit var chatDot: View
    private lateinit var musicBar: LinearLayout
    private lateinit var musicTitle: TextView
    private lateinit var musicPlay: ImageView

    private val chatListener = object : ChatHub.Listener {
        override fun onChatChanged() = refreshChatDots()
        override fun onNeighbours(count: Int) = refreshTravellers()
    }

    private val musicListener = object : MusicPlayer.Listener {
        override fun onPlayerChanged() = refreshMusicBar()
    }

    private val hideVoyant = Runnable { voyant.visibility = View.GONE }
    private val hideConfirmation = Runnable { closeConfirmation() }
    private val recenter = Runnable { resumeFollowing() }
    private val statusTick = object : Runnable {
        override fun run() {
            refreshGpsBanner()
            handler.postDelayed(this, STATUS_TICK_MS)
        }
    }

    private val tracker = object : TrackerListener {
        override fun onLocation(location: Location) = showLocation(location)

        override fun onAlerts(alerts: List<Alert>) = renderAlerts(alerts)

        override fun onProximity(alert: Alert, distanceMeters: Int) = showVoyant()

        override fun onConfirmationRequest(alert: Alert) = showConfirmation(alert)

        override fun onGpsState(enabled: Boolean) {
            refreshGpsBanner()
        }

        override fun onMessage(text: String) {
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        language = if (isArabicUi()) LabelLanguage.ARABIC else LabelLanguage.FRENCH
        MapLibre.getInstance(this)
        AlertsRepository.init(this)
        ChatHub.init(this)
        MusicLibrary.init(this)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener { message -> showStatus("Erreur carte : $message") }
        setContentView(buildLayout())
        if (hasFineLocation()) onLocationReady()
        if (!allOk()) showPermissionDialog()
        prepareMap()
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setTextColor(BUTTON_DARK)
        background = rounded(Color.WHITE, 16, Color.rgb(225, 233, 229))
        elevation = dp(5).toFloat()
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun isDeviceGpsEnabled(): Boolean = runCatching {
        (getSystemService(LOCATION_SERVICE) as LocationManager)
            .isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)

    private fun notificationsEnabled(): Boolean = runCatching {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }.getOrDefault(true)

    private fun batteryOk(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    private fun locationOk() = hasFineLocation() && isDeviceGpsEnabled()

    private fun notifOk() = notificationsEnabled()

    /** Les trois autorisations exigées : localisation (GPS), notifications et optimisation batterie. */
    private fun allOk() = locationOk() && notifOk() && batteryOk()

    private fun hasFreshFix(): Boolean {
        val location = TrackerHub.lastLocation ?: return false
        return System.currentTimeMillis() - location.time <= SEARCH_STALE_MS
    }

    /** Message d'état en haut : rouge si le GPS ou les notifications manquent (obligatoires), bleu pendant la recherche du signal. */
    private fun refreshGpsBanner() {
        if (!::gpsBanner.isInitialized) return
        val arabic = language == LabelLanguage.ARABIC
        systemState = when {
            !locationOk() -> SystemState.NO_GPS
            !notifOk() -> SystemState.NO_NOTIFICATIONS
            !batteryOk() -> SystemState.NO_BATTERY
            allOk() && !hasFreshFix() -> SystemState.SEARCHING
            else -> SystemState.OK
        }
        val red = Color.rgb(183, 43, 39)
        val blue = Color.rgb(0, 108, 255)
        val darkRed = Color.rgb(110, 18, 16)
        val darkBlue = Color.rgb(0, 55, 140)
        val (text, color) = when (systemState) {
            SystemState.NO_GPS -> Pair(
                if (arabic) "فعّل نظام تحديد الموقع (GPS) 📡" else "📡 Activez la localisation (GPS)", red,
            )
            SystemState.NO_NOTIFICATIONS -> Pair(
                if (arabic) "فعّل الإشعارات 🔔" else "🔔 Activez les notifications", red,
            )
            SystemState.NO_BATTERY -> Pair(
                if (arabic) "عطّل تحسين البطارية 🔋" else "🔋 Désactivez l'optimisation batterie", red,
            )
            SystemState.SEARCHING -> Pair(
                if (arabic) "جارٍ البحث عن إشارة GPS... 📡" else "📡 Recherche du signal GPS…", blue,
            )
            SystemState.OK -> {
                gpsBanner.visibility = View.GONE
                return
            }
        }
        gpsBanner.text = text
        gpsBanner.background = rounded(color, 16, if (color == red) darkRed else darkBlue)
        gpsBanner.visibility = View.VISIBLE
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun fixNotifications() {
        val needsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntime && (!askedNotifications ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        } else {
            openAppSettings()
        }
    }

    private fun fixBattery() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        if (opened.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun fixLocation() {
        if (hasFineLocation()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } else if (!askedLocation || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            askedLocation = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST)
        } else {
            openAppSettings()
        }
    }

    /**
     * Fenêtre des autorisations, chacune avec son bouton Activer ou une coche.
     * Le GPS est obligatoire : tant qu'il manque, la fenêtre ne se ferme pas et le bouton Plus tard est caché.
     */
    private fun showPermissionDialog() {
        if (permissionDialog?.isShowing == true) return
        val arabic = isArabic()
        val content = dialogCard()
        content.addView(TextView(this).apply {
            text = if (arabic) "الأذونات المطلوبة" else "Autorisations nécessaires"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = if (arabic) {
                "فعّل هذه الأذونات ليعمل التطبيق والتنبيهات أثناء القيادة"
            } else {
                "Activez ces autorisations pour que l'appli et les alertes fonctionnent pendant la conduite"
            }
            textSize = 14f
            setTextColor(Color.rgb(100, 116, 107))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        permissionRows = rows
        lateinit var dialog: AlertDialog
        val later = Button(this).apply {
            text = if (arabic) "لاحقاً" else "Plus tard"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(104, 116, 109), 16)
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(later, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })
        permissionLater = later
        dialog = showCard(content)
        dialog.setOnDismissListener {
            permissionDialog = null
            permissionRows = null
            permissionLater = null
        }
        permissionDialog = dialog
        refreshPermissionDialog()
    }

    private fun refreshPermissionDialog() {
        val rows = permissionRows ?: return
        if (allOk()) {
            permissionDialog?.dismiss()
            return
        }
        val gpsReady = locationOk()
        permissionDialog?.setCancelable(gpsReady)
        permissionLater?.visibility = if (gpsReady) View.VISIBLE else View.GONE
        val arabic = isArabic()
        rows.removeAllViews()
        // Icône au bord de la ligne, texte au milieu, bouton de l'autre côté (ordre inversé en arabe).
        fun row(icon: String, label: String, ok: Boolean, fix: () -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val iconView = TextView(this).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
            }
            val labelView = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.rgb(24, 53, 42))
                gravity = (if (arabic) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
            }
            val action: View = if (ok) {
                TextView(this).apply {
                    text = "✓"
                    textSize = 22f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(22, 125, 79))
                    gravity = Gravity.CENTER
                }
            } else {
                Button(this).apply {
                    text = if (arabic) "تفعيل" else "Activer"
                    isAllCaps = false
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = rounded(ORANGE, 14)
                    minWidth = 0
                    minHeight = 0
                    setOnClickListener { fix() }
                }
            }
            val iconParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
            val labelParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val actionParams = LinearLayout.LayoutParams(dp(88), dp(42))
            if (arabic) {
                line.addView(action, actionParams)
                line.addView(labelView, labelParams)
                line.addView(iconView, iconParams)
            } else {
                line.addView(iconView, iconParams)
                line.addView(labelView, labelParams)
                line.addView(action, actionParams)
            }
            rows.addView(line)
        }
        row("📡", if (arabic) "الموقع (GPS)" else "Localisation (GPS)", locationOk()) { fixLocation() }
        row("🔔", if (arabic) "الإشعارات" else "Notifications", notifOk()) { fixNotifications() }
        row("🔋", if (arabic) "بدون تحسين البطارية" else "Sans optimisation batterie", batteryOk()) { fixBattery() }
    }

    private fun buildLayout(): View {
        // Sans découpe, l'ombre des boutons ronds peut déborder de leur cadre (bouton ☰ et bouton chat avec leur point rouge).
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(238, 243, 239))
            clipChildren = false
        }
        root.addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        speedText = TextView(this).apply {
            text = "0"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val unitText = TextView(this).apply {
            text = "km/h"
            textSize = 11f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val speedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(speedText)
            addView(unitText)
            elevation = dp(5).toFloat()
        }
        root.addView(speedPanel, FrameLayout.LayoutParams(
            dp(CIRCLE_DP), dp(CIRCLE_DP), Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(12), dp(12), 0, 0) })

        // 3e cercle, sous les voyageurs : distance et durée restantes de l'itinéraire (toucher : arrêter l'itinéraire).
        routeDistanceText = TextView(this).apply {
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ROUTE_BLUE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        routeTimeText = TextView(this).apply {
            textSize = 11f
            setTextColor(ROUTE_BLUE)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        routeCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(2), ROUTE_BLUE)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(routeDistanceText)
            addView(routeTimeText)
            elevation = dp(5).toFloat()
            visibility = View.GONE
            isClickable = true
            setOnClickListener { confirmStopRoute() }
        }
        root.addView(routeCircle, FrameLayout.LayoutParams(
            dp(CIRCLE_DP), dp(CIRCLE_DP), Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(12), dp(circleTop(3)), 0, 0) })
        // Petit ✕ bleu (couleur de l'itinéraire) posé sur le bord du cercle : annuler l'itinéraire.
        routeCancel = TextView(this).apply {
            text = "✕"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ROUTE_BLUE)
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(7).toFloat()
            visibility = View.GONE
            contentDescription = "Annuler l'itinéraire"
            isClickable = true
            setOnClickListener { confirmStopRoute() }
        }
        root.addView(routeCancel, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(12 + CIRCLE_DP - 26), dp(circleTop(3) - 6), 0, 0) })

        // 2e cercle, toujours à la même place sous la vitesse : voyageurs proches reliés par Bluetooth.
        travellersText = TextView(this).apply {
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        travellersBluetooth = ImageView(this)
        travellersCircle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = oval(Color.WHITE, BUTTON_DARK, 2)
            elevation = dp(5).toFloat()
            contentDescription = "Voyageurs proches"
            isClickable = true
            setOnClickListener { openScreen(ChatActivity::class.java) }
            // La silhouette, avec le petit symbole Bluetooth en haut à droite de la tête.
            addView(FrameLayout(this@MainActivity).apply {
                addView(ImageView(this@MainActivity).apply { setImageDrawable(PersonDrawable(BUTTON_DARK)) },
                    FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.START))
                addView(travellersBluetooth, FrameLayout.LayoutParams(dp(15), dp(15), Gravity.TOP or Gravity.END))
            }, LinearLayout.LayoutParams(dp(38), dp(30)))
            addView(travellersText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }
        root.addView(travellersCircle, FrameLayout.LayoutParams(dp(CIRCLE_DP), dp(CIRCLE_DP), Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(12), dp(circleTop(2)), 0, 0) })

        voyant = TextView(this).apply {
            text = "!"
            textSize = 44f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(210, 52, 48))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            contentDescription = "تحذير قريب"
        }
        root.addView(voyant, FrameLayout.LayoutParams(dp(CIRCLE_DP), dp(CIRCLE_DP), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(12), dp(12), 0) })

        gpsBanner = TextView(this).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(10), dp(6), dp(10), dp(6))
            elevation = dp(5).toFloat()
            visibility = View.GONE
            // Le texte rétrécit pour tenir sur une seule ligne, sans passer sous le compteur de vitesse.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            } else {
                textSize = 12f
            }
        }
        root.addView(gpsBanner, FrameLayout.LayoutParams(
            resources.displayMetrics.widthPixels - dp(12 + CIRCLE_DP + 8 + 12), dp(46),
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, dp(12), dp(12), 0) })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            background = rounded(Color.rgb(28, 43, 37), 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation = dp(8).toFloat()
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        confirmBox = buildConfirmBox()
        root.addView(confirmBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(dp(12), dp(circleTop(3) + 4), dp(12), 0) })

        val reportButton = Button(this).apply {
            text = "+"
            textSize = 38f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, dp(3))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ORANGE)
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(10).toFloat()
            contentDescription = "الإبلاغ عن خطر"
            setOnClickListener { onReportClicked() }
        }
        root.addView(reportButton, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(0, 0, 0, dp(12)) })

        val routeButton = menuIconButton(SearchDrawable(BUTTON_DARK), "Itinéraire vers une destination") { openRoutePlanner() }
        val chatButton = menuIconButton(ChatDrawable(BUTTON_DARK), "Chat des voyageurs") { openScreen(ChatActivity::class.java) }
        chatDot = redDot()
        val musicButton = menuIconButton(MusicDrawable(BUTTON_DARK), "Musique") { openScreen(MusicActivity::class.java) }
        val helpButton = smallButton("?") { showHelp() }.apply {
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = "Aide"
            background = roundButtonBackground()
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        // Menu ouvert : la loupe toujours en haut, puis chat, musique et aide, au-dessus du bouton ☰.
        extraButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            clipToPadding = false
            clipChildren = false
            // Au-dessus de la barre de musique quand le menu s'ouvre.
            elevation = dp(8).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(routeButton, menuItemParams())
            addView(withDot(chatButton, chatDot), menuItemParams())
            addView(musicButton, menuItemParams())
            addView(helpButton, LinearLayout.LayoutParams(dp(ROUND_BUTTON_DP), dp(ROUND_BUTTON_DP)))
        }
        root.addView(extraButtons, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(14 - 6), dp(20 + ROUND_BUTTON_DP + 12 - 6)) })

        menuButton = smallButton("☰") { showMenu(extraButtons.visibility != View.VISIBLE) }.apply {
            textSize = 24f
            contentDescription = "Ouvrir les commandes"
            background = roundButtonBackground()
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        menuDot = redDot()
        root.addView(withDot(menuButton, menuDot), FrameLayout.LayoutParams(dp(ROUND_BUTTON_DP), dp(ROUND_BUTTON_DP), Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(14), dp(20)) })

        root.addView(buildMusicBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { setMargins(dp(12), 0, dp(12), dp(102)) })

        val locateButton = ImageView(this).apply {
            setImageDrawable(TargetDrawable(BUTTON_DARK))
            setPadding(dp(15), dp(15), dp(15), dp(15))
            contentDescription = "Recentrer sur ma position"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(210, 220, 214))
            }
            elevation = dp(5).toFloat()
            isClickable = true
            setOnClickListener { recenterAndZoom() }
        }
        root.addView(locateButton, FrameLayout.LayoutParams(dp(ROUND_BUTTON_DP), dp(ROUND_BUTTON_DP), Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(14), 0, 0, dp(20)) })
        return root
    }

    private fun roundButtonBackground() = oval(Color.WHITE, Color.rgb(210, 220, 214))

    /** Ouvre ou ferme le menu ; le bouton affiche ✕ tant que le menu est ouvert. */
    private fun showMenu(open: Boolean) {
        extraButtons.visibility = if (open) View.VISIBLE else View.GONE
        menuButton.text = if (open) "✕" else "☰"
    }

    private fun menuItemParams() = LinearLayout.LayoutParams(dp(ROUND_BUTTON_DP), dp(ROUND_BUTTON_DP)).apply { bottomMargin = dp(12) }

    private fun menuIconButton(icon: Drawable, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(icon)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        contentDescription = description
        background = roundButtonBackground()
        elevation = dp(5).toFloat()
        isClickable = true
        setOnClickListener {
            showMenu(false)
            onClick()
        }
    }

    private fun redDot() = View(this).apply {
        background = oval(Color.rgb(210, 52, 48), Color.WHITE, 2)
        elevation = dp(8).toFloat()
        visibility = View.GONE
    }

    /** Bouton rond avec un point rouge en haut à droite (nouveaux messages du chat). */
    private fun withDot(button: View, dot: View) = FrameLayout(this).apply {
        clipChildren = false
        addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(dot, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END))
    }

    private fun openScreen(screen: Class<out Activity>) {
        startActivity(Intent(this, screen))
    }

    private fun refreshChatDots() {
        val visible = if (ChatHub.unread > 0) View.VISIBLE else View.GONE
        chatDot.visibility = visible
        menuDot.visibility = visible
    }

    /**
     * Nombre de voyageurs proches et symbole Bluetooth bleu ; tiret et symbole gris quand le Bluetooth du chat
     * est éteint ou pas encore autorisé (toucher le cercle ouvre le chat, qui le demande).
     */
    private fun refreshTravellers() {
        val count = ChatHub.neighbourCount
        val off = Color.rgb(160, 170, 165)
        travellersText.text = if (ChatHub.active) count.toString() else "–"
        travellersText.setTextColor(if (ChatHub.active && count > 0) TEXT_DARK else off)
        travellersBluetooth.setImageDrawable(BluetoothDrawable(if (ChatHub.active) ROUTE_BLUE else off))
    }

    /** La fenêtre de confirmation se place sous le dernier cercle affiché à gauche, pour ne pas cacher l'itinéraire. */
    private fun placeConfirmBox() {
        val last = if (routeCircle.visibility == View.VISIBLE) 3 else 2
        (confirmBox.layoutParams as FrameLayout.LayoutParams).topMargin = dp(circleTop(last) + CIRCLE_DP + 14)
        confirmBox.requestLayout()
    }

    /** Barre de lecture sur toute la largeur, au-dessus du bouton +, avec de gros boutons faciles en conduisant. */
    private fun buildMusicBar(): View {
        musicTitle = TextView(this).apply {
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { openScreen(MusicActivity::class.java) }
        }
        musicPlay = musicBarButton(PlayerIcon.Shape.PAUSE, main = true) { MusicPlayer.toggle() }
        musicBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 30, Color.rgb(226, 233, 228))
            elevation = dp(4).toFloat()
            setPadding(dp(10), dp(6), dp(6), dp(6))
            visibility = View.GONE
            addView(musicTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(musicPlay)
            addView(musicBarButton(PlayerIcon.Shape.NEXT) { MusicPlayer.next() })
            addView(musicBarButton(PlayerIcon.Shape.CLOSE) { MusicPlayer.stop() })
        }
        return musicBar
    }

    private fun musicBarButton(shape: PlayerIcon.Shape, main: Boolean = false, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(PlayerIcon(shape, if (main) Color.WHITE else BUTTON_DARK))
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = if (main) oval(ORANGE, ORANGE) else oval(Color.rgb(241, 244, 242), Color.rgb(226, 233, 228))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(ROUND_BUTTON_DP), dp(ROUND_BUTTON_DP)).apply { marginStart = dp(6) }
    }

    private fun refreshMusicBar() {
        val playing = MusicPlayer.current
        musicBar.visibility = if (playing == null) View.GONE else View.VISIBLE
        musicTitle.text = playing?.title.orEmpty()
        val shape = if (MusicPlayer.isPlaying) PlayerIcon.Shape.PAUSE else PlayerIcon.Shape.PLAY
        musicPlay.setImageDrawable(PlayerIcon(shape, Color.WHITE))
    }

    private fun answerButton(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(52)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = rounded(color, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildConfirmBox(): LinearLayout {
        confirmTitle = TextView(this).apply {
            text = if (language == LabelLanguage.ARABIC) "هل التبليغ صحيح؟" else "Ce signalement est-il exact ?"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        }
        val yes = answerButton("👍 نعم", Color.rgb(22, 125, 79)) {
            pendingConfirmation?.let { AlertsRepository.confirm(it.id) }
            closeConfirmation()
        }
        val no = answerButton("👎 لا", Color.rgb(184, 52, 47)) { closeConfirmation() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(yes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            addView(no, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 20, Color.rgb(226, 233, 228))
            setPadding(dp(16), dp(22), dp(16), dp(22))
            elevation = dp(10).toFloat()
            visibility = View.GONE
            addView(confirmTitle)
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            })
        }
    }

    private fun updatePlaceLabels() {
        val style = map?.style ?: return
        val expression = if (language == LabelLanguage.ARABIC) {
            Expression.coalesce(Expression.get("name_ar"), Expression.get("name"), Expression.get("name_fr"))
        } else {
            Expression.coalesce(Expression.get("name_fr"), Expression.get("name"), Expression.get("name_ar"))
        }
        listOf("places-city", "places-town", "places-village", "places-suburb", "places-hamlet", "base-labels")
            .forEach { id -> style.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.textField(expression)) }
    }

    private fun smoothBearing(location: Location): Double {
        if (location.hasBearing() && location.hasSpeed() && location.speed >= MIN_BEARING_SPEED) {
            val delta = ((location.bearing - mapBearing + 540.0) % 360.0) - 180.0
            mapBearing = (mapBearing + delta * 0.4 + 360.0) % 360.0
        }
        return mapBearing
    }

    /** Centre la carte sur la position et la tourne pour que le sens de la route soit vers le haut. */
    private fun followCamera(location: Location, resetZoom: Boolean): Boolean {
        val currentMap = map ?: return false
        if (currentMap.style == null) return false
        val zoom = if (resetZoom) TRACKING_ZOOM else currentMap.cameraPosition.zoom
        val position = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(zoom)
            .bearing(smoothBearing(location))
            .tilt(0.0)
            .build()
        currentMap.animateCamera(CameraUpdateFactory.newCameraPosition(position), CAMERA_ANIM_MS)
        return true
    }

    /** L'utilisateur a bougé la carte : on arrête de la suivre, puis on se recentre seul après un moment. */
    private fun pauseFollowing() {
        followLocation = false
        handler.removeCallbacks(recenter)
        handler.postDelayed(recenter, RECENTER_DELAY_MS)
    }

    private fun resumeFollowing() {
        handler.removeCallbacks(recenter)
        followLocation = true
        val location = TrackerHub.lastLocation ?: return
        if (System.currentTimeMillis() - location.time <= MAX_GPS_AGE_MS) {
            followCamera(location, resetZoom = true)
        }
    }

    private fun recenterAndZoom() {
        val location = TrackerHub.lastLocation
        if (location == null || System.currentTimeMillis() - location.time > MAX_GPS_AGE_MS) {
            Toast.makeText(
                this,
                if (language == LabelLanguage.ARABIC) "انتظر إشارة GPS" else "En attente de la position GPS",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        handler.removeCallbacks(recenter)
        followLocation = true
        followCamera(location, resetZoom = true)
    }

    // ---------------------------------------------------------------------------------------------
    // Itinéraire hors ligne : de ma position vers une ville, une commune ou un village
    // ---------------------------------------------------------------------------------------------

    private fun isArabic() = language == LabelLanguage.ARABIC

    private fun dialogCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 24)
        setPadding(dp(22), dp(24), dp(22), dp(22))
    }

    private fun showCard(content: LinearLayout, resize: Boolean = false): AlertDialog {
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).create()
        if (resize) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    private fun openRoutePlanner() {
        showMenu(false)
        if (routingGraph != null) {
            showDestinationPicker()
            return
        }
        val routingFile = OfflineData.file(this, OfflineData.Part.ROUTING)
        if (!routingFile.exists()) {
            Toast.makeText(
                this,
                if (isArabic()) "أعد تشغيل التطبيق لتحميل المسارات" else "Relancez l'appli pour télécharger les itinéraires",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        Toast.makeText(this, if (isArabic()) "جارٍ التحميل…" else "Chargement…", Toast.LENGTH_SHORT).show()
        Thread {
            val outcome = runCatching {
                val graph = RoutingGraph(routingFile)
                graph to graph.loadPlaces()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val pair = outcome.getOrNull()
                if (pair == null) {
                    // Fichier abîmé : il sera téléchargé de nouveau au prochain lancement.
                    OfflineData.discard(this, OfflineData.Part.ROUTING)
                    Toast.makeText(
                        this,
                        if (isArabic()) "ملف المسارات تالف، أعد تشغيل التطبيق" else "Fichier d'itinéraires illisible : relancez l'appli",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    routingGraph = pair.first
                    routePlaces = pair.second
                    showDestinationPicker()
                }
            }
        }.start()
    }

    /**
     * 16 - Bouira - البويرة : numéro de wilaya (TN pour la Tunisie), nom français sans accents, nom arabe.
     * Le repère gauche-à-droite (U+200E) garde le numéro à gauche même quand il n'y a que le nom arabe.
     */
    private fun placeLine(place: RoutePlace): String {
        val parts = ArrayList<String>(3)
        when {
            place.wilaya == RoutePlace.TUNISIA -> parts.add("TN")
            place.wilaya > 0 -> parts.add(String.format(Locale.US, "%02d", place.wilaya))
        }
        listOf(withoutAccents(place.nameFr.ifEmpty { place.name }), place.nameAr)
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { parts.add(it) }
        return "\u200E" + parts.joinToString(" - ")
    }

    /** Aïn Témouchent devient Ain Temouchent ; seules les lettres latines sont touchées. */
    private fun withoutAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val result = StringBuilder(decomposed.length)
        for (i in decomposed.indices) {
            val char = decomposed[i]
            val isLatinMark = Character.getType(char) == Character.NON_SPACING_MARK.toInt() &&
                i > 0 && decomposed[i - 1] < '\u0250'
            if (!isLatinMark) result.append(char)
        }
        return result.toString()
    }

    /** Choix de la destination ; le départ est toujours la position GPS. */
    private fun showDestinationPicker() {
        val arabic = isArabic()
        lateinit var dialog: AlertDialog
        var shown: List<RoutePlace> = emptyList()
        val resultsAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>())

        fun refresh(query: String) {
            shown = searchPlaces(routePlaces, query, 40)
            resultsAdapter.clear()
            resultsAdapter.addAll(shown.map { placeLine(it) })
        }

        val search = EditText(this).apply {
            hint = if (arabic) "إلى أين؟ مدينة أو بلدية" else "Où allez-vous ? Ville ou commune"
            textSize = 17f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(Color.rgb(246, 248, 247), 14, Color.rgb(210, 220, 214))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(editable: Editable?) {
                    refresh(editable?.toString().orEmpty())
                }
            })
        }
        val list = ListView(this).apply {
            adapter = resultsAdapter
            setOnItemClickListener { _, _, position, _ ->
                val choice = shown.getOrNull(position) ?: return@setOnItemClickListener
                dialog.dismiss()
                computeRoute(choice)
            }
        }
        val content = dialogCard()
        content.addView(search)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(12) })
        refresh("")
        dialog = showCard(content, resize = true)
    }

    /** [rerouting] : recalcul discret après une sortie du tracé (pas de message, pas de cadrage du trajet). */
    private fun computeRoute(to: RoutePlace, rerouting: Boolean = false) {
        val graph = routingGraph ?: return
        val arabic = isArabic()
        if (routeBusy) {
            if (!rerouting) {
                Toast.makeText(this, if (arabic) "جارٍ حساب المسار…" else "Calcul en cours, réessayez dans un instant", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val location = TrackerHub.lastLocation
        if (location == null || System.currentTimeMillis() - location.time > MAX_GPS_AGE_MS) {
            if (!rerouting) {
                Toast.makeText(this, if (arabic) "في انتظار إشارة GPS…" else "En attente de la position GPS…", Toast.LENGTH_SHORT).show()
            }
            return
        }
        routeBusy = true
        if (!rerouting) {
            Toast.makeText(this, if (arabic) "جارٍ حساب المسار…" else "Calcul de l'itinéraire…", Toast.LENGTH_SHORT).show()
        }
        Thread {
            val result = runCatching {
                graph.route(location.latitude, location.longitude, to.latitude, to.longitude) { false }
            }.getOrNull()
            runOnUiThread {
                routeBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Itinéraire arrêté ou destination changée pendant le recalcul : on ignore le résultat.
                if (rerouting && (!routeShown || routeDestination !== to)) return@runOnUiThread
                if (result == null || result.latitudes.size < 2) {
                    if (!rerouting) {
                        Toast.makeText(
                            this,
                            if (arabic) "لم يتم العثور على مسار نحو هذه الوجهة" else "Aucun itinéraire trouvé vers cette destination",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    routeDestination = to
                    showRoute(result, frame = !rerouting)
                }
            }
        }.start()
    }

    private fun showRoute(result: RouteResult, frame: Boolean) {
        routeLatitudes = result.latitudes
        routeLongitudes = result.longitudes
        routeCumulative = DoubleArray(routeLatitudes.size)
        for (i in 1 until routeLatitudes.size) {
            routeCumulative[i] = routeCumulative[i - 1] +
                distanceMeters(routeLatitudes[i - 1], routeLongitudes[i - 1], routeLatitudes[i], routeLongitudes[i])
        }
        routeSeconds = result.seconds
        routeIndex = 0
        offRouteSince = 0L
        routeShown = true
        drawRemainingRoute(0, routeLatitudes[0], routeLongitudes[0])
        showRemaining(routeCumulative.last())
        routeCircle.visibility = View.VISIBLE
        routeCancel.visibility = View.VISIBLE
        placeConfirmBox()
        if (!frame) return

        // On montre tout le trajet un moment, puis la carte revient seule sur la voiture.
        pauseFollowing()
        runCatching {
            var north = -90.0
            var south = 90.0
            var east = -180.0
            var west = 180.0
            for (i in routeLatitudes.indices) {
                north = maxOf(north, routeLatitudes[i])
                south = minOf(south, routeLatitudes[i])
                east = maxOf(east, routeLongitudes[i])
                west = minOf(west, routeLongitudes[i])
            }
            val bounds = LatLngBounds.from(north, east, south, west)
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(70)))
        }
    }

    /** Trace la partie restante : depuis le point (latitude, longitude) placé sur le segment [from], jusqu'à l'arrivée. */
    private fun drawRemainingRoute(from: Int, latitude: Double, longitude: Double) {
        val last = routeLatitudes.size - 1
        val builder = StringBuilder((last - from + 2) * 24 + 128)
        builder.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        builder.append('[').append(longitude).append(',').append(latitude).append(']')
        for (i in from + 1..last) {
            builder.append(",[").append(routeLongitudes[i]).append(',').append(routeLatitudes[i]).append(']')
        }
        builder.append("]}}")
        map?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(builder.toString())
    }

    private fun showRemaining(meters: Double) {
        val arabic = isArabic()
        val km = meters / 1000.0
        val distance = if (km < 10) String.format(Locale.US, "%.1f", km) else km.roundToInt().toString()
        routeDistanceText.text = if (arabic) "$distance كم" else "$distance km"
        val total = routeCumulative.last()
        val seconds = if (total > 0) routeSeconds * meters / total else 0.0
        val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        routeTimeText.text = when {
            hours > 0 && arabic -> "$hours س $rest د"
            hours > 0 -> String.format(Locale.US, "%d h %02d", hours, rest)
            arabic -> "$rest د"
            else -> "$rest min"
        }
    }

    /**
     * Place la voiture sur le tracé (on ne cherche que vers l'avant, sur quelques km) et efface la partie déjà parcourue.
     * Hors du tracé depuis [OFF_ROUTE_RECALC_MS], l'itinéraire est recalculé depuis la position actuelle.
     */
    private fun updateRouteProgress(location: Location) {
        if (!routeShown) return
        val last = routeLatitudes.size - 1
        val cosLat = kotlin.math.cos(Math.toRadians(location.latitude))
        fun x(lon: Double) = (lon - location.longitude) * 111_320.0 * cosLat
        fun y(lat: Double) = (lat - location.latitude) * 110_574.0
        var bestDistance = Double.MAX_VALUE
        var bestIndex = routeIndex
        var bestT = 0.0
        val limit = routeCumulative[routeIndex] + ROUTE_LOOKAHEAD_METERS
        var i = routeIndex
        while (i < last && routeCumulative[i] <= limit) {
            val ax = x(routeLongitudes[i])
            val ay = y(routeLatitudes[i])
            val dx = x(routeLongitudes[i + 1]) - ax
            val dy = y(routeLatitudes[i + 1]) - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared > 0) ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val distance = kotlin.math.sqrt(px * px + py * py)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
                bestT = t
            }
            i++
        }
        if (bestDistance > ON_ROUTE_METERS) {
            val now = System.currentTimeMillis()
            if (offRouteSince == 0L) {
                offRouteSince = now
            } else if (now - offRouteSince >= OFF_ROUTE_RECALC_MS && !routeBusy) {
                // Si le recalcul échoue, on réessaie après le même délai.
                offRouteSince = now
                routeDestination?.let { computeRoute(it, rerouting = true) }
            }
            return
        }
        offRouteSince = 0L
        routeIndex = bestIndex
        val latitude = routeLatitudes[bestIndex] + bestT * (routeLatitudes[bestIndex + 1] - routeLatitudes[bestIndex])
        val longitude = routeLongitudes[bestIndex] + bestT * (routeLongitudes[bestIndex + 1] - routeLongitudes[bestIndex])
        val done = routeCumulative[bestIndex] + bestT * (routeCumulative[bestIndex + 1] - routeCumulative[bestIndex])
        val remaining = routeCumulative[last] - done
        if (remaining <= ARRIVAL_METERS) {
            clearRoute()
            Toast.makeText(this, if (isArabic()) "🏁 وصلت إلى وجهتك" else "🏁 Vous êtes arrivé", Toast.LENGTH_LONG).show()
            return
        }
        drawRemainingRoute(bestIndex, latitude, longitude)
        showRemaining(remaining)
    }

    private fun confirmStopRoute() {
        val arabic = isArabic()
        AlertDialog.Builder(this)
            .setMessage(if (arabic) "إلغاء المسار؟" else "Annuler l'itinéraire ?")
            .setPositiveButton(if (arabic) "نعم، إلغاء" else "Oui, annuler") { _, _ -> clearRoute() }
            .setNegativeButton(if (arabic) "لا" else "Non", null)
            .show()
    }

    private fun clearRoute() {
        map?.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        routeCircle.visibility = View.GONE
        routeCancel.visibility = View.GONE
        placeConfirmBox()
        routeShown = false
        routeDestination = null
        routeLatitudes = DoubleArray(0)
        routeLongitudes = DoubleArray(0)
        routeCumulative = DoubleArray(0)
    }

    private fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun hasFineLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasFineLocation()) onLocationReady()
        refreshGpsBanner()
        refreshPermissionDialog()
    }

    private fun onLocationReady() {
        map?.style?.let { enableLocation(it) }
        // GPS tracking starts by default; reporting and speed depend on location updates.
        TrackingService.start(this)
        refreshGpsBanner()
    }

    /** La carte a été téléchargée au premier lancement (écran SetupActivity). */
    private fun prepareMap() {
        showStatus("Préparation de la carte…")
        mapUrl = "mbtiles://${OfflineData.file(this, OfflineData.Part.MAP).absolutePath}"
        mapView.getMapAsync { onMapReady(it) }
    }

    private fun onMapReady(readyMap: MapLibreMap) {
        map = readyMap
        // Les crédits de la carte sont affichés dans la fenêtre d'aide, sans encombrer la carte.
        readyMap.uiSettings.setAttributionEnabled(false)
        readyMap.uiSettings.setLogoEnabled(false)
        // Pas de boussole : le bouton cible en bas à gauche remet la carte dans le sens de la route.
        readyMap.uiSettings.setCompassEnabled(false)
        readyMap.setMinZoomPreference(MIN_ZOOM)
        readyMap.setLatLngBoundsForCameraTarget(CAMERA_BOUNDS)
        readyMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(START_LATITUDE, START_LONGITUDE))
            .zoom(START_ZOOM)
            .build()
        readyMap.addOnMapClickListener { showAlertInfo(it) }
        readyMap.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) pauseFollowing()
        }
        reloadStyle()
    }

    private fun reloadStyle() {
        val currentMap = map ?: return
        val template = assets.open(STYLE_FILE).bufferedReader().use { it.readText() }
        val json = template
            .replace("{{MBTILES_URL}}", mapUrl)
            .replace("\"{{TEXT_FIELD}}\"", language.expression)
        val builder = Style.Builder().fromJson(json)
            .withImage(AlertTypes.DANGER, alertIcon(AlertTypes.EMOJI))
        currentMap.setStyle(builder) { style ->
            showStatus(null)
            loadBaseLayer()
            renderAlerts(TrackerHub.alerts)
            enableLocation(style)
            TrackerHub.lastLocation?.let { showLocation(it) }
        }
    }

    /** Mer, pays voisins et terres algériennes : fichier téléchargé, lu hors du fil principal. */
    private fun loadBaseLayer() {
        val file = OfflineData.file(this, OfflineData.Part.BASE)
        Thread {
            val text = runCatching { file.readText() }.getOrNull() ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                map?.style?.getSourceAs<GeoJsonSource>(BASE_SOURCE)?.setGeoJson(text)
            }
        }.start()
    }

    private fun alertIcon(emoji: String): Bitmap {
        val size = dp(ALERT_ICON_DP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val whiteRing = size * 0.07f
        val orangeRing = size * 0.11f
        fun disk(radius: Float, fill: Int) =
            canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        disk(center, Color.argb(70, 0, 0, 0))
        disk(center - dp(1), Color.WHITE)
        disk(center - dp(1) - whiteRing, ORANGE)
        disk(center - dp(1) - whiteRing - orangeRing, Color.WHITE)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.38f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(emoji, center, center - (glyph.descent() + glyph.ascent()) / 2, glyph)
        return bitmap
    }

    private fun renderAlerts(alerts: List<Alert>) {
        // Les signalements à moins de 50 m sont regroupés sur une seule icône.
        val groups = ArrayList<AlertGroup>()
        alerts.forEach { alert ->
            val group = groups.firstOrNull {
                distanceMeters(it.latitude, it.longitude, alert.latitude, alert.longitude) < GROUP_RADIUS_METERS
            } ?: AlertGroup(alert.latitude, alert.longitude).also { groups.add(it) }
            group.add(alert)
        }
        val features = JSONArray()
        groups.forEach { group ->
            val properties = JSONObject()
                .put("icon", AlertTypes.DANGER)
                .put("total", group.total)
                .put("comments", group.comments.joinToString("\n"))
                .put("photos", group.photos.joinToString("\n"))
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(group.longitude).put(group.latitude)))
                    .put("properties", properties),
            )
        }
        val collection = JSONObject().put("type", "FeatureCollection").put("features", features)
        map?.style?.getSourceAs<GeoJsonSource>(ALERT_SOURCE)?.setGeoJson(collection.toString())
    }

    private fun showAlertInfo(latLng: LatLng): Boolean {
        val currentMap = map ?: return false
        val point = currentMap.projection.toScreenLocation(latLng)
        val radius = dp(TAP_RADIUS_DP).toFloat()
        val area = RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
        val features = currentMap.queryRenderedFeatures(area, ALERT_LAYER, ALERT_CLUSTER_LAYER)
        if (features.isEmpty()) return false
        val single = features.firstOrNull { !it.hasProperty("point_count") }
        if (single != null) {
            val total = single.getNumberProperty("total")?.toInt() ?: 1
            val comments = single.getStringProperty("comments").orEmpty().split("\n").filter { it.isNotBlank() }
            val photos = single.getStringProperty("photos").orEmpty().split("\n").filter { it.isNotBlank() }
            showGroupPopup(total, comments, photos)
            return true
        }
        // Un cluster : on zoome pour séparer les icônes.
        pauseFollowing()
        currentMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(latLng, currentMap.cameraPosition.zoom + CLUSTER_TAP_ZOOM_STEP),
        )
        return true
    }

    private fun showGroupPopup(total: Int, comments: List<String>, photos: List<String>) {
        val arabic = language == LabelLanguage.ARABIC
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24)
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "${AlertTypes.EMOJI} " + if (arabic) AlertTypes.LABEL_AR else AlertTypes.LABEL_FR
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = if (arabic) "عدد التبليغات ($total)" else "Signalé $total fois"
            textSize = 16f
            setTextColor(Color.rgb(60, 76, 68))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        comments.take(5).forEach { comment ->
            content.addView(TextView(this).apply {
                text = "💬  $comment"
                textSize = 15f
                setTextColor(Color.rgb(88, 102, 95))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }
        if (photos.isNotEmpty()) content.addView(photoGrid(photos))
        val close = Button(this).apply {
            text = if (arabic) "إغلاق" else "Fermer"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(104, 116, 109), 16)
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(20)
        })
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this).setView(scroll).create()
        // Se ferme automatiquement après 30 secondes.
        val autoClose = Runnable { if (dialog.isShowing) dialog.dismiss() }
        dialog.setOnDismissListener { handler.removeCallbacks(autoClose) }
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        handler.postDelayed(autoClose, POPUP_AUTO_CLOSE_MS)
    }

    /** Miniatures carrées, toutes de la même taille, 3 par ligne ; toucher une photo l'agrandit. */
    private fun photoGrid(photos: List<String>): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        photos.chunked(3).forEach { line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            line.forEach { url ->
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(Color.rgb(236, 240, 237), 12)
                    clipToOutline = true
                    setOnClickListener { showPhoto(url) }
                }
                row.addView(thumb, LinearLayout.LayoutParams(dp(PHOTO_THUMB_DP), dp(PHOTO_THUMB_DP)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
                ImageLoader.load(this, url, thumb, dp(PHOTO_THUMB_DP))
            }
            grid.addView(row)
        }
        return grid
    }

    private fun showPhoto(url: String) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val dialog = AlertDialog.Builder(this).setView(image).create()
        image.setOnClickListener { dialog.dismiss() }
        dialog.show()
        ImageLoader.load(this, url, image, resources.displayMetrics.widthPixels)
    }

    private fun enableLocation(style: Style) {
        if (!hasFineLocation()) return
        val component = map?.locationComponent ?: return
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(true)
                .build()
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.NORMAL
        component.cameraMode = CameraMode.NONE
    }

    private fun showLocation(location: Location) {
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt().coerceAtLeast(0) else 0
        speedText.text = kmh.toString()
        updateRouteProgress(location)
        if (followLocation && System.currentTimeMillis() - location.time <= MAX_GPS_AGE_MS) {
            // Premier point GPS : on se place sur la position avec le zoom de conduite.
            if (followCamera(location, resetZoom = !positioned)) positioned = true
        }
        refreshGpsBanner()
    }

    private fun showVoyant() {
        voyant.visibility = View.VISIBLE
        handler.removeCallbacks(hideVoyant)
        handler.postDelayed(hideVoyant, VOYANT_MS)
    }

    private fun showConfirmation(alert: Alert) {
        if (confirmBox.visibility == View.VISIBLE) return
        pendingConfirmation = alert
        confirmBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideConfirmation)
        handler.postDelayed(hideConfirmation, CONFIRM_MS)
    }

    private fun closeConfirmation() {
        handler.removeCallbacks(hideConfirmation)
        confirmBox.visibility = View.GONE
        pendingConfirmation = null
    }

    /** Change la langue de l'appli et des noms de la carte, sans recharger la carte ni bouger la caméra. */
    private fun switchLanguage() {
        language = if (language == LabelLanguage.ARABIC) LabelLanguage.FRENCH else LabelLanguage.ARABIC
        saveArabicUi(language == LabelLanguage.ARABIC)
        refreshGpsBanner()
        confirmTitle.text = if (language == LabelLanguage.ARABIC) "هل التبليغ صحيح؟" else "Ce signalement est-il exact ?"
        updatePlaceLabels()
    }

    /** Fenêtre d'aide : ce que fait l'appli, en quelques lignes. */
    private fun showHelp() {
        showMenu(false)
        val arabic = isArabic()
        lateinit var dialog: AlertDialog
        val content = dialogCard()
        content.addView(TextView(this).apply {
            text = if (arabic) "كيف يعمل طريقي" else "Comment marche Tariki"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
        })
        val side = if (arabic) Gravity.END else Gravity.START
        helpEntries(arabic).forEach { (title, explanation) ->
            content.addView(TextView(this).apply {
                text = title
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(TEXT_DARK)
                gravity = side
                setPadding(0, dp(18), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(TextView(this).apply {
                text = explanation
                textSize = 15f
                setTextColor(TEXT_SOFT)
                gravity = side
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(TextView(this).apply {
            text = "© OpenStreetMap contributors (ODbL) · Natural Earth"
            textSize = 12f
            setTextColor(TEXT_SOFT)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        content.addView(
            actionButton(if (arabic) "Français" else "العربية", BUTTON_DARK, bold = true) {
                dialog.dismiss()
                switchLanguage()
                showHelp()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(24) },
        )
        content.addView(
            actionButton(if (arabic) "اتصل بنا" else "Nous contacter", ROUTE_BLUE, bold = true) {
                dialog.dismiss()
                showContactDialog()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) },
        )
        content.addView(
            actionButton(if (arabic) "إغلاق" else "Fermer", BUTTON_GREY) { dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) },
        )
        dialog = showCard(content)
    }

    /** Message au responsable de l'appli : téléphone ou e-mail facultatif, texte obligatoire. */
    private fun showContactDialog() {
        val arabic = isArabic()
        lateinit var dialog: AlertDialog
        val content = dialogCard()
        content.addView(TextView(this).apply {
            text = if (arabic) "اتصل بنا" else "Nous contacter"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
        })
        val contactField = formField(
            if (arabic) "رقم الهاتف أو البريد الإلكتروني (اختياري)" else "Téléphone ou e-mail (facultatif)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            120,
            lines = 1,
        )
        val messageField = formField(
            if (arabic) "رسالتك" else "Ton message",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            CONTACT_MAX_LENGTH,
            lines = 4,
        )
        content.addView(contactField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        content.addView(messageField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            bottomMargin = dp(26)
        })
        val cancel = actionButton(if (arabic) "إلغاء" else "Annuler", BUTTON_GREY) { dialog.dismiss() }
        val send = actionButton(if (arabic) "إرسال" else "Envoyer", ROUTE_BLUE, bold = true) {
            val message = messageField.text.toString().trim()
            if (message.isEmpty()) {
                messageField.error = if (arabic) "اكتب رسالتك" else "Écris ton message"
                return@actionButton
            }
            dialog.dismiss()
            AlertsRepository.submitContact(contactField.text.toString(), message)
        }
        content.addView(buttonRow(cancel, send))
        dialog = showCard(content, resize = true)
    }

    private fun formField(hintText: String, type: Int, maxLength: Int, lines: Int) = EditText(this).apply {
        hint = hintText
        textSize = 16f
        setTextColor(TEXT_DARK)
        setHintTextColor(Color.rgb(140, 152, 146))
        inputType = type
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
        minLines = lines
        maxLines = lines + 2
        gravity = Gravity.TOP or Gravity.START
        background = rounded(Color.rgb(246, 248, 247), 16, Color.rgb(210, 220, 214))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun actionButton(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(color, 16)
        setOnClickListener { onClick() }
    }

    private fun buttonRow(left: Button, right: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(left, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(0, 0, dp(10), 0) })
        addView(right, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(10), 0, 0, 0) })
    }

    /** Un seul type, danger sur la route, avec une description facultative. */
    private fun onReportClicked() {
        val arabic = isArabic()
        val location = TrackerHub.lastLocation
        if (location == null || System.currentTimeMillis() - location.time > MAX_GPS_AGE_MS) {
            Toast.makeText(
                this,
                if (arabic) "📡 فعّل نظام تحديد الموقع" else "📡 Activez la localisation GPS",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        lateinit var dialog: AlertDialog
        val content = dialogCard()
        content.addView(TextView(this).apply {
            text = if (arabic) "⚠️ أبلغ عن خطر" else "⚠️ Signaler un danger"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
        })
        val commentField = EditText(this).apply {
            hint = if (arabic) "أضف وصفاً للخطر (اختياري)" else "Décrivez le danger (facultatif)"
            textSize = 16f
            setTextColor(Color.rgb(24, 53, 42))
            setHintTextColor(Color.rgb(140, 152, 146))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(COMMENT_MAX_LENGTH))
            gravity = Gravity.TOP or Gravity.START
            background = rounded(Color.rgb(246, 248, 247), 16, Color.rgb(210, 220, 214))
            minLines = 3
            // Le texte s'arrête avant l'appareil photo : à droite en français, à gauche en arabe.
            val clear = dp(CAMERA_DP + 14)
            if (arabic) setPadding(clear, dp(10), dp(14), dp(10)) else setPadding(dp(14), dp(10), clear, dp(10))
        }

        // Photo facultative : l'appareil photo dans le coin du champ, puis la miniature avec un ✕ pour la retirer.
        var photo: File? = null
        var keepPhoto = false
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(236, 240, 237), 12)
            clipToOutline = true
        }
        val removePhoto = TextView(this).apply {
            text = "✕"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = oval(BUTTON_GREY, Color.WHITE, 2)
        }
        val photoBox = FrameLayout(this).apply {
            visibility = View.GONE
            addView(thumbnail, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.BOTTOM or Gravity.START))
            addView(removePhoto, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))
        }
        val cameraButton = ImageView(this).apply {
            setImageDrawable(CameraDrawable(BUTTON_DARK))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = oval(Color.rgb(228, 233, 230), Color.rgb(210, 220, 214))
            contentDescription = if (arabic) "إضافة صورة" else "Ajouter une photo"
            isClickable = true
            setOnClickListener {
                takePhoto { taken ->
                    if (taken == null) return@takePhoto
                    photo?.delete()
                    photo = taken
                    thumbnail.setImageBitmap(PhotoTools.decodeSampled(taken, dp(72)))
                    photoBox.visibility = View.VISIBLE
                }
            }
        }
        val commentBox = FrameLayout(this).apply {
            addView(commentField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val corner = Gravity.BOTTOM or if (arabic) Gravity.LEFT else Gravity.RIGHT
            addView(cameraButton, FrameLayout.LayoutParams(dp(CAMERA_DP), dp(CAMERA_DP), corner).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        content.addView(commentBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(COMMENT_BOX_DP)).apply {
            topMargin = dp(28)
        })
        removePhoto.setOnClickListener {
            photo?.delete()
            photo = null
            photoBox.visibility = View.GONE
        }
        content.addView(photoBox, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            topMargin = dp(12)
            gravity = if (arabic) Gravity.RIGHT else Gravity.LEFT
        })

        val cancel = actionButton(if (arabic) "إلغاء" else "Annuler", BUTTON_GREY) { dialog.dismiss() }
        val next = actionButton(if (arabic) "تبليغ" else "Signaler", ORANGE, bold = true) {
            keepPhoto = true
            dialog.dismiss()
            confirmReport(location.latitude, location.longitude, commentField.text.toString().trim(), photo)
        }
        content.addView(buttonRow(cancel, next), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(36)
        })
        dialog = showCard(content, resize = true)
        dialog.setOnDismissListener { if (!keepPhoto) photo?.delete() }
    }

    /** Ouvre l'appareil photo ; [onReady] reçoit la photo préparée (800 px), ou null si annulé ou illisible. */
    private fun takePhoto(onReady: (File?) -> Unit) {
        val capture = File(File(cacheDir, "photos").apply { mkdirs() }, "capture.jpg")
        capture.delete()
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.photos", capture) }.getOrNull() ?: return
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        photoCapture = capture
        photoReady = onReady
        try {
            startActivityForResult(intent, PHOTO_REQUEST)
        } catch (exception: ActivityNotFoundException) {
            photoCapture = null
            photoReady = null
            Toast.makeText(this, if (isArabic()) "لا يوجد تطبيق كاميرا" else "Aucune appli appareil photo", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("startActivityForResult : appareil photo du téléphone")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PHOTO_REQUEST) return
        val capture = photoCapture
        val callback = photoReady ?: return
        photoCapture = null
        photoReady = null
        if (resultCode != RESULT_OK || capture == null || !capture.exists() || capture.length() == 0L) {
            callback(null)
            return
        }
        val target = File(File(filesDir, "pending-photos").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
        Thread {
            val ok = PhotoTools.prepare(capture, target)
            capture.delete()
            runOnUiThread { callback(if (ok) target else null) }
        }.start()
    }

    private fun confirmReport(latitude: Double, longitude: Double, comment: String, photo: File?) {
        val arabic = isArabic()
        lateinit var dialog: AlertDialog
        val content = dialogCard()
        content.addView(TextView(this).apply {
            text = if (arabic) "تأكيد البلاغ" else "Confirmer le signalement"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 53, 42))
            gravity = Gravity.CENTER
        })
        val label = "${AlertTypes.EMOJI}  " + if (arabic) AlertTypes.LABEL_AR else AlertTypes.LABEL_FR
        val description = (if (comment.isEmpty()) "" else "\n💬  $comment") +
            (if (photo == null) "" else "\n📷  " + if (arabic) "صورة مرفقة" else "Photo jointe")
        val question = if (arabic) {
            "هل تريد إرسال هذا البلاغ إلى السائقين الآخرين؟"
        } else {
            "Voulez-vous partager ce signalement avec les autres conducteurs ?"
        }
        content.addView(TextView(this).apply {
            text = "$label$description\n\n$question"
            textSize = 16f
            setTextColor(Color.rgb(60, 76, 68))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(30))
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        var sent = false
        val cancel = actionButton(if (arabic) "إلغاء" else "Annuler", BUTTON_GREY) { dialog.dismiss() }
        val send = actionButton(if (arabic) "إرسال البلاغ" else "Envoyer", ORANGE, bold = true) {
            sent = true
            dialog.dismiss()
            AlertsRepository.submitReport(AlertTypes.DANGER, latitude, longitude, comment, photo?.path.orEmpty())
        }
        content.addView(buttonRow(cancel, send))
        dialog = showCard(content, resize = true)
        dialog.setOnDismissListener { if (!sent) photo?.delete() }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        TrackerHub.add(tracker)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        tracker.onGpsState(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        renderAlerts(TrackerHub.alerts)
        TrackerHub.lastLocation?.let { showLocation(it) }
        AlertsRepository.sync()
        ChatHub.add(chatListener)
        ChatHub.acquire(this)
        MusicPlayer.add(musicListener)
        refreshChatDots()
        refreshTravellers()
        refreshMusicBar()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
        if (!locationOk()) showPermissionDialog() else refreshPermissionDialog()
        ChatHub.refresh()
        refreshTravellers()
        if (isDeviceGpsEnabled() && hasFineLocation() && !TrackerHub.trackingActive) {
            TrackingService.start(this)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        TrackerHub.remove(tracker)
        ChatHub.remove(chatListener)
        ChatHub.release()
        MusicPlayer.remove(musicListener)
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        if (isFinishing) MusicPlayer.stop()
        handler.removeCallbacksAndMessages(null)
        routingGraph?.close()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
