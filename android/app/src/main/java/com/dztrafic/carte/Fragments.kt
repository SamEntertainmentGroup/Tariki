package com.dztrafic.carte

/**
 * Découpage des trames en petits paquets Bluetooth et réassemblage à l'arrivée.
 * En-tête de 5 octets : numéro de trame (1), index du fragment (2), nombre de fragments (2).
 * Sur une même liaison, les fragments arrivent dans l'ordre (garanti par le Bluetooth LE).
 */
object Fragments {
    const val ENTETE = 5
    const val MAX_FRAGMENTS = 4_000
    const val OCTETS_MAX = 64_000

    fun decouper(trame: ByteArray, taillePaquet: Int, numero: Int): List<ByteArray> {
        val utile = taillePaquet - ENTETE
        require(utile > 0) { "paquet trop petit" }
        val total = maxOf(1, (trame.size + utile - 1) / utile)
        require(total <= MAX_FRAGMENTS) { "trame trop grande" }
        return (0 until total).map { i ->
            val debut = i * utile
            val fin = minOf(trame.size, debut + utile)
            val p = ByteArray(ENTETE + fin - debut)
            p[0] = numero.toByte()
            p[1] = (i shr 8).toByte()
            p[2] = i.toByte()
            p[3] = (total shr 8).toByte()
            p[4] = total.toByte()
            System.arraycopy(trame, debut, p, ENTETE, fin - debut)
            p
        }
    }

    class Assembleur {
        private var numero = -1
        private var attendu = 0
        private var total = 0
        private val tampon = java.io.ByteArrayOutputStream()

        /** Renvoie la trame complète quand le dernier fragment arrive, sinon null. */
        fun recevoir(p: ByteArray): ByteArray? {
            if (p.size < ENTETE) return null
            val n = p[0].toInt() and 0xff
            val i = ((p[1].toInt() and 0xff) shl 8) or (p[2].toInt() and 0xff)
            val t = ((p[3].toInt() and 0xff) shl 8) or (p[4].toInt() and 0xff)
            if (t == 0 || t > MAX_FRAGMENTS || i >= t) return null
            if (i == 0) {
                numero = n
                total = t
                attendu = 0
                tampon.reset()
            } else if (n != numero || t != total || i != attendu) {
                numero = -1
                return null
            }
            tampon.write(p, ENTETE, p.size - ENTETE)
            if (tampon.size() > OCTETS_MAX) {
                numero = -1
                return null
            }
            attendu = i + 1
            if (attendu < total) return null
            numero = -1
            return tampon.toByteArray()
        }
    }
}
