package com.dztrafic.carte

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/** Liaison avec un téléphone voisin, quel que soit le côté qui a ouvert la connexion. */
interface Lien {
    val id: String
    val estClient: Boolean
    var noeud: String?
    /** Les trames prioritaires (présentation, inventaire) passent avant les messages. */
    fun envoyer(trame: ByteArray, prioritaire: Boolean)
    fun fermer()
}

/**
 * Liaison Bluetooth Low Energy du chat (reprise de Jirani), sans aucune bibliothèque externe.
 * Chaque téléphone est à la fois :
 * - serveur : il se rend visible et accepte les connexions de ses voisins ;
 * - client : il cherche les voisins visibles et s'y connecte.
 * Les trames sont découpées en paquets à la taille négociée (MTU). Tout tourne sur le fil principal :
 * les rappels Bluetooth, qui arrivent sur d'autres fils, y sont renvoyés.
 */
class TransportBle(
    private val context: Context,
    private val monNoeud: String,
    private val ecouteur: Ecouteur
) {
    interface Ecouteur {
        fun lienOuvert(l: Lien)
        fun trameRecue(l: Lien, trame: ByteArray)
        fun lienFerme(l: Lien)
    }

    companion object {
        // Identifiants propres à Tariki : ses téléphones ne se relient pas à ceux de Jirani.
        val SERVICE: UUID = UUID.fromString("3b8f5c20-7d14-4e6a-b2f9-61c0d4a7e5b1")
        val CARACTERISTIQUE: UUID = UUID.fromString("3b8f5c21-7d14-4e6a-b2f9-61c0d4a7e5b1")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_CLIENTS = 3
        private const val MAX_LIENS = 6
        private const val MTU_DEMANDE = 517
        private const val PAQUET_MIN = 20
        private const val PAQUET_MAX = 512
        private const val DELAI_CONNEXION_MS = 15_000L
        private const val PAUSE_APRES_ECHEC_MS = 60_000L
        private const val DELAI_SECOND_ESSAI_MS = 20_000L
        private const val ENVOI_BLOQUE_MS = 20_000L
        private const val SURVEILLANCE_MS = 10_000L
        /** Au-delà, les plus anciennes trames de messages en attente sont abandonnées. */
        private const val MAX_TRAMES_EN_ATTENTE = 40
    }

    private val fil = Handler(Looper.getMainLooper())
    private val gestionnaire: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private var serveur: BluetoothGattServer? = null
    private var caracServeur: BluetoothGattCharacteristic? = null
    private var annonceEnCours = false
    private var scanner: BluetoothLeScanner? = null
    private var actif = false
    private var numeroTrame = 0

    private val clients = HashMap<String, LienClient>()
    private val serveurs = HashMap<String, LienServeur>()
    private val mtuServeur = HashMap<String, Int>()
    private val echecs = HashMap<String, Long>()
    private val secondsEssais = HashSet<String>()

    val nombreLiens: Int get() = clients.values.count { it.pret } + serveurs.values.count { it.pret }

    fun demarrer() {
        if (actif) return
        val adaptateur = gestionnaire?.adapter ?: return
        if (!adaptateur.isEnabled) return
        actif = true
        sur { ouvrirServeur() }
        sur { demarrerRecherche() }
        fil.postDelayed(surveillance, SURVEILLANCE_MS)
    }

    fun arreter() {
        if (!actif) return
        actif = false
        fil.removeCallbacksAndMessages(null)
        sur { scanner?.stopScan(rappelRecherche) }
        scanner = null
        sur {
            if (annonceEnCours) gestionnaire?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(rappelAnnonce)
        }
        annonceEnCours = false
        (clients.values + serveurs.values).toList().forEach { it.fermer() }
        sur { serveur?.close() }
        serveur = null
        caracServeur = null
        mtuServeur.clear()
        secondsEssais.clear()
    }

    /** Les téléphones ralentissent parfois une recherche longue : on la redémarre régulièrement. */
    fun relancerRecherche() {
        if (!actif) return
        sur {
            scanner?.stopScan(rappelRecherche)
            demarrerRecherche()
        }
    }

    // --- Serveur et visibilité ---

    private fun ouvrirServeur() {
        val s = gestionnaire?.openGattServer(context, rappelServeur) ?: return
        val carac = BluetoothGattCharacteristic(
            CARACTERISTIQUE,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        carac.addDescriptor(
            BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(carac)
        serveur = s
        caracServeur = carac
        s.addService(service)
    }

    private fun demarrerAnnonce() {
        val adaptateur = gestionnaire?.adapter ?: return
        // Sans visibilité possible, le téléphone se connecte seulement aux autres.
        if (!adaptateur.isMultipleAdvertisementSupported) return
        val annonceur = adaptateur.bluetoothLeAdvertiser ?: return
        val reglages = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val donnees = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val reponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(SERVICE), versOctets(monNoeud))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        annonceur.startAdvertising(reglages, donnees, reponse, rappelAnnonce)
        annonceEnCours = true
    }

    private val rappelAnnonce = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            annonceEnCours = false
        }
    }

    private val rappelServeur = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            fil.post {
                if (!actif) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) sur { demarrerAnnonce() }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val adresse = device.address
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fil.post {
                    mtuServeur.remove(adresse)
                    serveurs[adresse]?.let { it.ferme = true; retirer(it) }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val adresse = device.address
            fil.post {
                mtuServeur[adresse] = mtu
                serveurs[adresse]?.taillePaquet = taillePourMtu(mtu)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val copie = value?.copyOf()
            if (responseNeeded) repondre(device, requestId, null)
            if (copie == null || characteristic.uuid != CARACTERISTIQUE) return
            fil.post { lienServeur(device)?.recu(copie) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val active = descriptor.uuid == CCCD &&
                value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) repondre(device, requestId, null)
            if (!active) return
            fil.post {
                val l = lienServeur(device) ?: return@post
                if (!l.pret) {
                    l.pret = true
                    ecouteur.lienOuvert(l)
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            repondre(device, requestId, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val adresse = device.address
            fil.post { serveurs[adresse]?.envoiTermine(status == BluetoothGatt.GATT_SUCCESS) }
        }
    }

    private fun repondre(device: BluetoothDevice, requestId: Int, valeur: ByteArray?) {
        sur { serveur?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, valeur) }
    }

    /** Liaison côté serveur créée au premier échange réel (pas sur une simple connexion Bluetooth). */
    private fun lienServeur(device: BluetoothDevice): LienServeur? {
        if (!actif) return null
        val adresse = device.address
        serveurs[adresse]?.let { return it }
        if (clients.size + serveurs.size >= MAX_LIENS) {
            sur { serveur?.cancelConnection(device) }
            return null
        }
        val l = LienServeur(device)
        mtuServeur[adresse]?.let { l.taillePaquet = taillePourMtu(it) }
        serveurs[adresse] = l
        return l
    }

    // --- Recherche et connexions sortantes ---

    private fun demarrerRecherche() {
        val s = gestionnaire?.adapter?.bluetoothLeScanner ?: return
        val filtre = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val reglages = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        s.startScan(listOf(filtre), reglages, rappelRecherche)
        scanner = s
    }

    private val rappelRecherche = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            fil.post { voisinTrouve(result) }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            fil.post { results.forEach { voisinTrouve(it) } }
        }
    }

    private fun voisinTrouve(r: ScanResult) {
        if (!actif) return
        val noeud = r.scanRecord?.getServiceData(ParcelUuid(SERVICE))?.let { versHex(it) } ?: return
        if (noeud == monNoeud || noeudRelie(noeud)) return
        val device = r.device
        val adresse = device.address
        if (clients.containsKey(adresse)) return
        val echec = echecs[adresse]
        if (echec != null && SystemClock.elapsedRealtime() - echec < PAUSE_APRES_ECHEC_MS) return
        // Pour éviter deux connexions croisées, le téléphone au plus petit identifiant se connecte ;
        // l'autre ne tente qu'après un délai, au cas où le premier ne le verrait pas.
        if (monNoeud < noeud) {
            connecter(device, noeud)
        } else if (secondsEssais.add(noeud)) {
            fil.postDelayed({
                secondsEssais.remove(noeud)
                if (actif && !noeudRelie(noeud) && !clients.containsKey(adresse)) connecter(device, noeud)
            }, DELAI_SECOND_ESSAI_MS)
        }
    }

    private fun noeudRelie(noeud: String): Boolean =
        clients.values.any { it.noeud == noeud } || serveurs.values.any { it.noeud == noeud }

    private fun connecter(device: BluetoothDevice, noeud: String) {
        if (clients.size >= MAX_CLIENTS || clients.size + serveurs.size >= MAX_LIENS) return
        val l = LienClient(device.address)
        l.noeud = noeud
        clients[l.adresse] = l
        val gatt = try {
            device.connectGatt(context, false, l.rappel, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            null
        }
        if (gatt == null) {
            echecs[l.adresse] = SystemClock.elapsedRealtime()
            clients.remove(l.adresse)
            return
        }
        l.gatt = gatt
        fil.postDelayed({ if (!l.pret && !l.ferme) echecConnexion(l) }, DELAI_CONNEXION_MS)
    }

    private fun echecConnexion(l: LienClient) {
        echecs[l.adresse] = SystemClock.elapsedRealtime()
        l.fermer()
    }

    // --- Liaisons ---

    private fun retirer(l: LienBle) {
        when (l) {
            is LienClient -> if (clients[l.adresse] === l) clients.remove(l.adresse)
            is LienServeur -> if (serveurs[l.adresse] === l) serveurs.remove(l.adresse)
        }
        if (l.pret) {
            l.pret = false
            ecouteur.lienFerme(l)
        }
    }

    private val surveillance = object : Runnable {
        override fun run() {
            if (!actif) return
            val maintenant = SystemClock.elapsedRealtime()
            (clients.values + serveurs.values).filter { it.enCours && maintenant - it.debutEnvoi > ENVOI_BLOQUE_MS }
                .forEach { it.fermer() }
            fil.postDelayed(this, SURVEILLANCE_MS)
        }
    }

    abstract inner class LienBle(val adresse: String) : Lien {
        override var noeud: String? = null
        var pret = false
        var ferme = false
        var enCours = false
        var debutEnvoi = 0L
        var taillePaquet = PAQUET_MIN
        private val prioritaires = java.util.ArrayDeque<ByteArray>()
        private val autres = java.util.ArrayDeque<ByteArray>()
        private val fragments = java.util.ArrayDeque<ByteArray>()
        private val assembleur = Fragments.Assembleur()

        abstract fun ecrire(paquet: ByteArray): Boolean

        override fun envoyer(trame: ByteArray, prioritaire: Boolean) {
            if (!pret || ferme) return
            if (prioritaire) {
                prioritaires.add(trame)
            } else {
                autres.add(trame)
                while (autres.size > MAX_TRAMES_EN_ATTENTE) autres.poll()
            }
            pousser()
        }

        private fun pousser() {
            if (enCours || ferme) return
            if (fragments.isEmpty()) {
                val trame = prioritaires.poll() ?: autres.poll() ?: return
                numeroTrame = (numeroTrame + 1) and 0xff
                Fragments.decouper(trame, taillePaquet, numeroTrame).forEach { fragments.add(it) }
            }
            val p = fragments.poll() ?: return
            enCours = true
            debutEnvoi = SystemClock.elapsedRealtime()
            val ok = try {
                ecrire(p)
            } catch (e: SecurityException) {
                false
            }
            if (!ok) {
                enCours = false
                fermer()
            }
        }

        fun envoiTermine(ok: Boolean) {
            if (!enCours) return
            enCours = false
            if (ok) pousser() else fermer()
        }

        fun recu(paquet: ByteArray) {
            if (ferme) return
            assembleur.recevoir(paquet)?.let { ecouteur.trameRecue(this, it) }
        }

        protected fun viderFile() {
            prioritaires.clear()
            autres.clear()
            fragments.clear()
        }
    }

    inner class LienClient(adresse: String) : LienBle(adresse) {
        override val id: String = "C-$adresse"
        override val estClient = true
        var gatt: BluetoothGatt? = null
        private var carac: BluetoothGattCharacteristic? = null

        @Suppress("DEPRECATION")
        override fun ecrire(paquet: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = carac ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, paquet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = paquet
                g.writeCharacteristic(c)
            }
        }

        override fun fermer() {
            if (ferme) return
            ferme = true
            viderFile()
            val g = gatt
            sur { g?.disconnect() }
            fil.postDelayed({ sur { g?.close() } }, 500)
            retirer(this)
        }

        @Suppress("DEPRECATION")
        private fun activerNotifications(g: BluetoothGatt) {
            val c = g.getService(SERVICE)?.getCharacteristic(CARACTERISTIQUE)
            val d = c?.getDescriptor(CCCD)
            if (c == null || d == null || !g.setCharacteristicNotification(c, true)) {
                echecConnexion(this)
                return
            }
            carac = c
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
            if (!ok) echecConnexion(this)
        }

        val rappel = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                fil.post {
                    if (ferme) return@post
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        fil.postDelayed({ if (!ferme) sur { if (!g.requestMtu(MTU_DEMANDE)) g.discoverServices() } }, 200)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!pret) echecs[adresse] = SystemClock.elapsedRealtime()
                        fermer()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                fil.post {
                    if (ferme) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) taillePaquet = taillePourMtu(mtu)
                    sur { if (!g.discoverServices()) echecConnexion(this@LienClient) }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                fil.post {
                    if (ferme) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        echecConnexion(this@LienClient)
                        return@post
                    }
                    sur { activerNotifications(g) }
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                fil.post {
                    if (ferme || pret) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        echecConnexion(this@LienClient)
                        return@post
                    }
                    pret = true
                    echecs.remove(adresse)
                    ecouteur.lienOuvert(this@LienClient)
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                fil.post { envoiTermine(status == BluetoothGatt.GATT_SUCCESS) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                val copie = value.copyOf()
                fil.post { recu(copie) }
            }

            @Deprecated("Android 12 et moins")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                val copie = characteristic.value?.copyOf() ?: return
                fil.post { recu(copie) }
            }
        }
    }

    inner class LienServeur(private val device: BluetoothDevice) : LienBle(device.address) {
        override val id: String = "S-$adresse"
        override val estClient = false

        @Suppress("DEPRECATION")
        override fun ecrire(paquet: ByteArray): Boolean {
            val s = serveur ?: return false
            val c = caracServeur ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, c, false, paquet) == BluetoothStatusCodes.SUCCESS
            } else {
                c.value = paquet
                s.notifyCharacteristicChanged(device, c, false)
            }
        }

        override fun fermer() {
            if (ferme) return
            ferme = true
            viderFile()
            sur { serveur?.cancelConnection(device) }
            retirer(this)
        }
    }

    // --- Outils ---

    private fun taillePourMtu(mtu: Int): Int = (mtu - 3).coerceIn(PAQUET_MIN, PAQUET_MAX)

    /** Exécute un appel Bluetooth en ignorant les refus d'autorisation et les erreurs de la pile Bluetooth. */
    private inline fun sur(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            // Autorisation retirée ou pile Bluetooth en erreur : l'appel est abandonné, le relais continue.
        }
    }

    private fun versOctets(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private fun versHex(b: ByteArray): String {
        val chiffres = "0123456789abcdef"
        val sb = StringBuilder()
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append(chiffres[v shr 4]).append(chiffres[v and 0x0f])
        }
        return sb.toString()
    }
}
