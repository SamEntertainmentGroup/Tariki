package com.dztrafic.carte

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

private const val TIMEOUT_MS = 15_000
private const val USER_AGENT = "Tariki/1.0"

/** Échanges avec le plugin WordPress (admin-ajax.php) : signalements, contact, chat et musique. */
class AlertsApi(private val endpoint: String) {

    class Result(val success: Boolean, val message: String, val confirmed: Boolean)

    class Track(val id: Long, val genre: String, val title: String, val url: String, val size: Long)

    fun fetchAlerts(): List<Alert> {
        val data = get("dztrafic_get_alerts").optJSONArray("data") ?: throw IOException("Réponse invalide")
        return (0 until data.length()).mapNotNull { index ->
            runCatching { Alert.fromJson(data.getJSONObject(index)) }.getOrNull()
        }
    }

    fun sendReport(report: PendingReport, deviceId: String): Result {
        val form = mapOf(
            "action" to "dztrafic_add_alert",
            "nonce" to fetchNonce(),
            "device_id" to deviceId,
            "type" to report.type,
            "latitude" to report.latitude.toString(),
            "longitude" to report.longitude.toString(),
            "comment" to report.comment.trim().take(140),
        )
        val photo = File(report.photoPath).takeIf { report.photoPath.isNotEmpty() && it.isFile }
        return toResult(if (photo != null) postMultipart(form, "photo", photo) else post(form))
    }

    fun confirm(alertId: Long, deviceId: String): Result = toResult(
        post(
            mapOf(
                "action" to "dztrafic_confirm_alert",
                "nonce" to fetchNonce(),
                "id" to alertId.toString(),
                "device_id" to deviceId,
            ),
        ),
    )

    fun sendContact(contact: PendingContact, deviceId: String): Result = toResult(
        post(
            mapOf(
                "action" to "tariki_contact",
                "nonce" to fetchNonce(),
                "device_id" to deviceId,
                "contact" to contact.contact,
                "message" to contact.message,
            ),
        ),
    )

    /** Envoie les messages du chat reçus par Bluetooth et les signalements ; renvoie les messages masqués. */
    fun syncChat(messages: JSONArray, reports: JSONArray, deviceId: String): Set<String> {
        val response = post(
            mapOf(
                "action" to "tariki_chat_sync",
                "nonce" to fetchNonce(),
                "device_id" to deviceId,
                "messages" to messages.toString(),
                "reports" to reports.toString(),
            ),
        )
        if (!response.optBoolean("success")) throw IOException("Chat refusé")
        return hiddenIds(response)
    }

    fun hiddenChat(): Set<String> = hiddenIds(get("tariki_chat_hidden"))

    fun fetchMusic(): List<Track> {
        val tracks = get("tariki_music").optJSONObject("data")?.optJSONArray("tracks") ?: throw IOException("Réponse invalide")
        return (0 until tracks.length()).mapNotNull { index ->
            val item = tracks.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            if (!url.startsWith("https://")) return@mapNotNull null
            Track(item.optLong("id"), item.optString("genre"), item.optString("title"), url, item.optLong("size"))
        }
    }

    private fun hiddenIds(response: JSONObject): Set<String> {
        val array = response.optJSONObject("data")?.optJSONArray("hidden") ?: return emptySet()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    private fun fetchNonce(): String {
        val response = get("dztrafic_get_nonce")
        val nonce = response.optJSONObject("data")?.optString("nonce").orEmpty()
        if (!response.optBoolean("success") || nonce.isEmpty()) throw IOException("Nonce invalide")
        return nonce
    }

    private fun toResult(response: JSONObject): Result {
        val data = response.optJSONObject("data")
        return Result(
            success = response.optBoolean("success"),
            message = data?.optString("message").orEmpty(),
            confirmed = data?.optBoolean("confirmation", false) ?: false,
        )
    }

    private fun get(action: String): JSONObject = request("?action=$action&_=${System.currentTimeMillis()}") { }

    private fun post(form: Map<String, String>): JSONObject = request("") { connection ->
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        val body = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun postMultipart(form: Map<String, String>, fileField: String, file: File): JSONObject = request("") { connection ->
        val boundary = "tariki" + UUID.randomUUID().toString().replace("-", "")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(connection.outputStream).use { out ->
            form.forEach { (key, value) ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray(Charsets.UTF_8))
            }
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"photo.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n").toByteArray(Charsets.UTF_8),
            )
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun request(query: String, send: (HttpURLConnection) -> Unit): JSONObject {
        val connection = URL(endpoint + query).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            send(connection)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("HTTP $code : réponse illisible") }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
