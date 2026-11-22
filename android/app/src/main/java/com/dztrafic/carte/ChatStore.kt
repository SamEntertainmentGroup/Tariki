package com.dztrafic.carte

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** Message du chat entre voyageurs : texte seulement, gardé 24 heures. */
data class ChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val replyTo: String,
    val sentAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", id)
        .put("a", author)
        .put("x", text)
        .put("r", replyTo)
        .put("s", sentAt)

    companion object {
        const val MAX_TEXT = 300
        const val LIFETIME_MS = 24 * 3_600_000L
        private const val FUTURE_MARGIN_MS = 10 * 60_000L
        private val ID = Regex("[0-9a-f]{16}")
        private val AUTHOR = Regex("[0-9a-f]{8}")

        /** Lit un message reçu ; null s'il est mal formé, trop long, trop vieux ou daté dans le futur. */
        fun fromJson(json: JSONObject, now: Long): ChatMessage? {
            val message = ChatMessage(
                id = json.optString("i"),
                author = json.optString("a"),
                text = json.optString("x").trim(),
                replyTo = json.optString("r"),
                sentAt = json.optLong("s"),
            )
            val valid = ID.matches(message.id) && AUTHOR.matches(message.author) &&
                message.text.isNotEmpty() && message.text.length <= MAX_TEXT &&
                (message.replyTo.isEmpty() || ID.matches(message.replyTo)) &&
                message.sentAt > now - LIFETIME_MS && message.sentAt < now + FUTURE_MARGIN_MS
            return if (valid) message else null
        }
    }
}

/**
 * Messages du chat sur le téléphone, avec ceux que l'utilisateur a masqués ou signalés et ceux masqués
 * par le modérateur. Tout est enregistré dans un seul petit fichier.
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, "chat.json")
    private val prefs = context.getSharedPreferences("dz_store", Context.MODE_PRIVATE)
    private val messages = LinkedHashMap<String, ChatMessage>()
    private val hidden = LinkedHashSet<String>()
    private val reportsToSend = LinkedHashSet<String>()
    private val uploaded = HashSet<String>()

    /** Code d'auteur tiré au hasard, sans lien avec le téléphone ni avec le Bluetooth. */
    val author: String = prefs.getString(KEY_AUTHOR, null) ?: randomHex(4).also { prefs.edit().putString(KEY_AUTHOR, it).apply() }

    init {
        load()
    }

    /** Identifiant Bluetooth du téléphone, changé chaque jour. */
    fun node(): String {
        val day = System.currentTimeMillis() / 86_400_000L
        val current = prefs.getString(KEY_NODE, null)
        if (current != null && prefs.getLong(KEY_NODE_DAY, -1L) == day) return current
        return randomHex(4).also { prefs.edit().putString(KEY_NODE, it).putLong(KEY_NODE_DAY, day).apply() }
    }

    @Synchronized
    fun create(text: String, replyTo: String): ChatMessage {
        val message = ChatMessage(randomHex(8), author, text.trim().take(ChatMessage.MAX_TEXT), replyTo, System.currentTimeMillis())
        messages[message.id] = message
        save()
        return message
    }

    /** Ajoute un message reçu ; false s'il est déjà connu, masqué ou si son auteur écrit trop. */
    @Synchronized
    fun add(message: ChatMessage): Boolean {
        if (messages.containsKey(message.id) || message.id in hidden) return false
        val recent = messages.values.count { it.author == message.author && message.sentAt - it.sentAt in 0..AUTHOR_WINDOW_MS }
        if (recent >= AUTHOR_MAX_MESSAGES) return false
        messages[message.id] = message
        save()
        return true
    }

    @Synchronized
    fun visible(): List<ChatMessage> {
        purge()
        return messages.values.filter { it.id !in hidden }.sortedBy { it.sentAt }
    }

    @Synchronized
    fun find(id: String): ChatMessage? = messages[id]

    @Synchronized
    fun knownIds(): List<String> {
        purge()
        return messages.keys.toList()
    }

    /** Messages à transmettre à un voisin qui connaît déjà [known] ; les messages masqués ne circulent plus. */
    @Synchronized
    fun missingFor(known: Set<String>): List<ChatMessage> =
        messages.values.filter { it.id !in known && it.id !in hidden }

    @Synchronized
    fun hide(id: String) {
        hidden.add(id)
        save()
    }

    @Synchronized
    fun report(id: String) {
        hidden.add(id)
        reportsToSend.add(id)
        save()
    }

    @Synchronized
    fun applyServerHidden(ids: Set<String>): Boolean {
        val changed = hidden.addAll(ids)
        if (changed) save()
        return changed
    }

    @Synchronized
    fun pendingUpload(): List<ChatMessage> = messages.values.filter { it.id !in uploaded }.take(UPLOAD_BATCH)

    @Synchronized
    fun pendingReports(): List<String> = reportsToSend.take(UPLOAD_BATCH)

    @Synchronized
    fun markSent(messageIds: Collection<String>, reportIds: Collection<String>) {
        uploaded.addAll(messageIds)
        reportsToSend.removeAll(reportIds.toSet())
        save()
    }

    private fun purge() {
        val limit = System.currentTimeMillis() - ChatMessage.LIFETIME_MS
        val old = messages.values.filter { it.sentAt < limit }.map { it.id }
        if (old.isEmpty()) return
        old.forEach { id ->
            messages.remove(id)
            uploaded.remove(id)
            reportsToSend.remove(id)
        }
        while (hidden.size > MAX_HIDDEN) hidden.remove(hidden.first())
        save()
    }

    private fun load() {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        json.optJSONArray("messages")?.let { array ->
            for (i in 0 until array.length()) {
                val message = array.optJSONObject(i)?.let { ChatMessage.fromJson(it, now) } ?: continue
                messages[message.id] = message
            }
        }
        readSet(json.optJSONArray("hidden"), hidden)
        readSet(json.optJSONArray("reports"), reportsToSend)
        readSet(json.optJSONArray("uploaded"), uploaded)
    }

    private fun save() {
        val json = JSONObject()
            .put("messages", JSONArray().apply { messages.values.forEach { put(it.toJson()) } })
            .put("hidden", JSONArray(hidden.toList()))
            .put("reports", JSONArray(reportsToSend.toList()))
            .put("uploaded", JSONArray(uploaded.toList()))
        val temporary = File(file.path + ".tmp")
        temporary.writeText(json.toString())
        temporary.renameTo(file)
    }

    private fun readSet(array: JSONArray?, target: MutableSet<String>) {
        if (array == null) return
        for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let { target.add(it) }
    }

    private companion object {
        const val KEY_AUTHOR = "chat_author"
        const val KEY_NODE = "chat_node"
        const val KEY_NODE_DAY = "chat_node_day"
        const val AUTHOR_WINDOW_MS = 10 * 60_000L
        const val AUTHOR_MAX_MESSAGES = 10
        const val UPLOAD_BATCH = 100
        const val MAX_HIDDEN = 2_000
        val random = SecureRandom()

        fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            random.nextBytes(data)
            return data.joinToString("") { "%02x".format(it) }
        }
    }
}
