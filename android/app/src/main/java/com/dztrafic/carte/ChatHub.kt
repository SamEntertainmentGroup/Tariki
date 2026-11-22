package com.dztrafic.carte

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chat entre voyageurs par Bluetooth, sans internet. Il ne tourne que pendant que l'appli est ouverte
 * (carte ou écran du chat visible). Échange avec un voisin : présentation, liste des messages connus,
 * puis envoi des messages qui lui manquent ; les nouveaux messages sont relayés aux autres voisins.
 */
object ChatHub : TransportBle.Ecouteur {

    interface Listener {
        fun onChatChanged() {}
        fun onNeighbours(count: Int) {}
    }

    private const val STOP_DELAY_MS = 3_000L
    private const val SCAN_RESTART_MS = 4 * 60_000L
    private const val BATCH = 20
    private const val MAX_PER_NEIGHBOUR_PER_MINUTE = 40
    private val NODE = Regex("[0-9a-f]{8}")

    private class Neighbour(val link: Lien) {
        val known = HashSet<String>()
        val arrivals = ArrayList<Long>()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val neighbours = HashMap<String, Neighbour>()
    private var appContext: Context? = null
    private var transport: TransportBle? = null
    private var node = ""
    private var users = 0

    var store: ChatStore? = null
        private set

    /** Messages des autres arrivés pendant que l'écran du chat était fermé. */
    var unread = 0
        private set

    /** Vrai quand l'écran du chat est affiché : les messages arrivés sont lus. */
    var viewing = false
        set(value) {
            field = value
            if (value && unread > 0) {
                unread = 0
                notifyChanged()
            }
        }

    val neighbourCount: Int
        get() = neighbours.size

    /** Vrai quand le Bluetooth du chat tourne (allumé et autorisé). */
    val active: Boolean
        get() = transport != null

    private val stopLater = Runnable { stopTransport() }
    private val restartScan = object : Runnable {
        override fun run() {
            transport?.relancerRecherche()
            main.postDelayed(this, SCAN_RESTART_MS)
        }
    }

    fun init(context: Context) {
        if (store != null) return
        appContext = context.applicationContext
        store = ChatStore(context.applicationContext)
    }

    fun add(listener: Listener) {
        listeners.add(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(context: Context): Boolean =
        requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Un écran de l'appli devient visible. */
    fun acquire(context: Context) {
        init(context)
        users++
        main.removeCallbacks(stopLater)
        refresh()
    }

    /** Un écran de l'appli n'est plus visible ; le Bluetooth s'arrête quand plus aucun ne l'est. */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) main.postDelayed(stopLater, STOP_DELAY_MS)
    }

    /** À appeler quand les autorisations ou l'état du Bluetooth ont pu changer. */
    fun refresh() {
        val context = appContext ?: return
        val chatStore = store ?: return
        if (transport != null && !bluetoothOn(context)) stopTransport()
        if (users == 0 || transport != null || !hasPermissions(context) || !bluetoothOn(context)) return
        node = chatStore.node()
        transport = TransportBle(context, node, this).also { it.demarrer() }
        main.postDelayed(restartScan, SCAN_RESTART_MS)
    }

    fun send(text: String, replyTo: String) {
        val chatStore = store ?: return
        val message = chatStore.create(text, replyTo)
        forward(listOf(message), except = null)
        notifyChanged()
        AlertsRepository.syncChat()
    }

    fun hide(id: String) {
        store?.hide(id)
        notifyChanged()
    }

    fun report(id: String) {
        store?.report(id)
        notifyChanged()
        AlertsRepository.syncChat()
    }

    fun notifyChanged() {
        listeners.forEach { it.onChatChanged() }
    }

    private fun stopTransport() {
        main.removeCallbacks(restartScan)
        transport?.arreter()
        transport = null
        neighbours.clear()
        listeners.forEach { it.onNeighbours(0) }
    }

    // --- Événements du Bluetooth (sur le fil principal) ---

    override fun lienOuvert(l: Lien) {
        neighbours[l.id] = Neighbour(l)
        l.envoyer(frame(JSONObject().put("t", "h").put("n", node)), true)
        l.envoyer(frame(JSONObject().put("t", "i").put("ids", JSONArray(store?.knownIds() ?: emptyList<String>()))), true)
        listeners.forEach { it.onNeighbours(neighbours.size) }
    }

    override fun lienFerme(l: Lien) {
        neighbours.remove(l.id)
        listeners.forEach { it.onNeighbours(neighbours.size) }
    }

    override fun trameRecue(l: Lien, trame: ByteArray) {
        val neighbour = neighbours[l.id] ?: return
        val json = runCatching { JSONObject(String(trame, Charsets.UTF_8)) }.getOrNull() ?: return
        when (json.optString("t")) {
            "h" -> hello(neighbour, json.optString("n"))
            "i" -> inventory(neighbour, json.optJSONArray("ids"))
            "m" -> receive(neighbour, json.optJSONArray("l"))
        }
    }

    private fun hello(neighbour: Neighbour, otherNode: String) {
        val link = neighbour.link
        if (!NODE.matches(otherNode) || otherNode == node) {
            link.fermer()
            return
        }
        link.noeud = otherNode
        // Deux liaisons avec le même téléphone : on garde la première.
        val duplicate = neighbours.values.any { it !== neighbour && it.link.noeud == otherNode }
        if (duplicate) {
            neighbours.remove(link.id)
            if (link.estClient) link.fermer()
            listeners.forEach { it.onNeighbours(neighbours.size) }
        }
    }

    private fun inventory(neighbour: Neighbour, ids: JSONArray?) {
        if (ids != null) for (i in 0 until ids.length()) neighbour.known.add(ids.optString(i))
        sendTo(neighbour, store?.missingFor(neighbour.known) ?: return)
    }

    private fun receive(neighbour: Neighbour, list: JSONArray?) {
        val chatStore = store ?: return
        if (list == null) return
        val now = SystemClock.elapsedRealtime()
        neighbour.arrivals.removeAll { now - it > 60_000L }
        val fresh = ArrayList<ChatMessage>()
        val wallClock = System.currentTimeMillis()
        for (i in 0 until list.length()) {
            if (neighbour.arrivals.size >= MAX_PER_NEIGHBOUR_PER_MINUTE) break
            val message = list.optJSONObject(i)?.let { ChatMessage.fromJson(it, wallClock) } ?: continue
            neighbour.known.add(message.id)
            if (chatStore.add(message)) {
                neighbour.arrivals.add(now)
                fresh.add(message)
            }
        }
        if (fresh.isEmpty()) return
        if (!viewing) unread += fresh.count { it.author != chatStore.author }
        forward(fresh, except = neighbour)
        notifyChanged()
        AlertsRepository.syncChat()
    }

    private fun forward(messages: List<ChatMessage>, except: Neighbour?) {
        neighbours.values.filter { it !== except }.forEach { sendTo(it, messages.filter { m -> m.id !in it.known }) }
    }

    private fun sendTo(neighbour: Neighbour, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        messages.forEach { neighbour.known.add(it.id) }
        messages.chunked(BATCH).forEach { batch ->
            val array = JSONArray().apply { batch.forEach { put(it.toJson()) } }
            neighbour.link.envoyer(frame(JSONObject().put("t", "m").put("l", array)), false)
        }
    }

    private fun frame(json: JSONObject): ByteArray = json.toString().toByteArray(Charsets.UTF_8)

    /** Voyageur 1234 : numéro à 4 chiffres tiré du code d'auteur. */
    fun displayName(author: String, arabic: Boolean): String {
        val number = ((author.toLongOrNull(16) ?: 0L) % 10_000).toString().padStart(4, '0')
        return if (arabic) "مسافر $number" else "Voyageur $number"
    }
}
