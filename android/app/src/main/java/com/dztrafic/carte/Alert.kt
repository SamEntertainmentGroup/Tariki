package com.dztrafic.carte

import org.json.JSONArray
import org.json.JSONObject

data class Alert(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    val title: String,
    val confirmations: Int,
    val ageSeconds: Long,
    val comments: List<String> = emptyList(),
    /** Adresses des photos validées par le modérateur. */
    val photos: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("lat", latitude)
        .put("lng", longitude)
        .put("type", type)
        .put("title", title)
        .put("confirmations", confirmations)
        .put("age", ageSeconds)
        .put("comments", JSONArray(comments))
        .put("photos", JSONArray(photos))

    companion object {
        fun fromJson(json: JSONObject) = Alert(
            id = json.getLong("id"),
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lng"),
            type = json.optString("type"),
            title = json.optString("title"),
            confirmations = json.optInt("confirmations", 1),
            ageSeconds = json.optLong("age", 0),
            comments = strings(json.optJSONArray("comments")),
            photos = strings(json.optJSONArray("photos")).filter { it.startsWith("https://") },
        )
    }
}

private fun strings(array: JSONArray?): List<String> =
    array?.let { (0 until it.length()).map { index -> it.optString(index) }.filter { text -> text.isNotBlank() } } ?: emptyList()

data class PendingReport(
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val createdAtMs: Long,
    val comment: String = "",
    /** Photo préparée sur le téléphone, envoyée avec le signalement puis effacée. */
    val photoPath: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("lat", latitude)
        .put("lng", longitude)
        .put("createdAt", createdAtMs)
        .put("comment", comment)
        .put("photo", photoPath)

    companion object {
        fun fromJson(json: JSONObject) = PendingReport(
            type = json.getString("type"),
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lng"),
            createdAtMs = json.optLong("createdAt", 0),
            comment = json.optString("comment"),
            photoPath = json.optString("photo"),
        )
    }
}

/** Message envoyé par le bouton Nous contacter, gardé tant qu'il n'est pas parti. */
data class PendingContact(val contact: String, val message: String, val createdAtMs: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("contact", contact)
        .put("message", message)
        .put("createdAt", createdAtMs)

    companion object {
        fun fromJson(json: JSONObject) = PendingContact(
            contact = json.optString("contact"),
            message = json.getString("message"),
            createdAtMs = json.optLong("createdAt", 0),
        )
    }
}

/** Un seul type de signalement : danger sur la route. Les alertes d'autres types venues du site s'affichent pareil. */
object AlertTypes {
    const val DANGER = "danger"
    const val EMOJI = "⚠️"
    const val LABEL_AR = "خطر على الطريق"
    const val LABEL_FR = "Danger sur la route"
}
