package com.dztrafic.carte

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val MIN_SEND_INTERVAL_MS = 35_000L
private const val TEXT_SENT = "✅ تم إرسال التبليغ"
private const val TEXT_CONFIRMED = "✅ تم تأكيد التبليغ"
private const val TEXT_QUEUED = "📥 تم حفظ التبليغ وسيُرسل عند توفر الاتصال"
private const val TEXT_NOT_CONFIGURED = "⚠️ عنوان الخادم غير مضبوط"
private const val TEXT_ERROR = "❌ حدث خطأ"
private const val TEXT_CONTACT_QUEUED = "📥 تم حفظ رسالتك وسترسل عند توفر الاتصال"
private const val TEXT_CONTACT_SENT = "✅ تم إرسال رسالتك، شكراً"

object AlertsRepository {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val chatBusy = AtomicBoolean(false)
    private var appContext: Context? = null
    private var store: AlertStore? = null
    private var api: AlertsApi? = null

    @Volatile
    private var lastSendMs = 0L

    fun init(context: Context) {
        if (store != null) return
        appContext = context.applicationContext
        val newStore = AlertStore(context.applicationContext)
        store = newStore
        api = BuildConfig.ALERTS_URL.takeIf { it.isNotBlank() }?.let { AlertsApi(it) }
        TrackerHub.setAlerts(newStore.loadAlerts())
    }

    val configured: Boolean
        get() = api != null

    val apiClient: AlertsApi?
        get() = api

    fun deviceId(): String = store?.deviceId().orEmpty()

    fun sync() {
        val client = api ?: return
        val storage = store ?: return
        syncChat()
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                try {
                    flushOnePending(client, storage)
                } catch (ignored: IOException) {
                }
                try {
                    flushOneContact(client, storage)
                } catch (ignored: IOException) {
                }
                val alerts = client.fetchAlerts()
                storage.saveAlerts(alerts)
                main.post { TrackerHub.setAlerts(alerts) }
            } catch (ignored: IOException) {
            } finally {
                busy.set(false)
            }
        }
    }

    fun submitReport(type: String, latitude: Double, longitude: Double, comment: String, photoPath: String) {
        val storage = store ?: return
        if (api == null) {
            TrackerHub.notifyMessage(TEXT_NOT_CONFIGURED)
            return
        }
        storage.addPending(PendingReport(type, latitude, longitude, System.currentTimeMillis(), comment.trim().take(140), photoPath))
        TrackerHub.notifyMessage(TEXT_QUEUED)
        sync()
    }

    fun submitContact(contact: String, message: String) {
        val storage = store ?: return
        if (api == null) {
            TrackerHub.notifyMessage(TEXT_NOT_CONFIGURED)
            return
        }
        storage.addContact(PendingContact(contact.trim().take(120), message.trim().take(1000), System.currentTimeMillis()))
        TrackerHub.notifyMessage(TEXT_CONTACT_QUEUED)
        sync()
    }

    /** Envoie au modérateur les messages du chat et les signalements, puis récupère les messages masqués. */
    fun syncChat() {
        val client = api ?: return
        val context = appContext ?: return
        val chat = ChatHub.store ?: return
        val storage = store ?: return
        if (!context.isOnline() || !chatBusy.compareAndSet(false, true)) return
        executor.execute {
            try {
                val messages = chat.pendingUpload()
                val reports = chat.pendingReports()
                val hidden = if (messages.isEmpty() && reports.isEmpty()) {
                    client.hiddenChat()
                } else {
                    val array = JSONArray().apply { messages.forEach { put(it.toJson()) } }
                    client.syncChat(array, JSONArray(reports), storage.deviceId()).also {
                        chat.markSent(messages.map { it.id }, reports)
                    }
                }
                if (chat.applyServerHidden(hidden)) main.post { ChatHub.notifyChanged() }
            } catch (ignored: IOException) {
            } finally {
                chatBusy.set(false)
            }
        }
    }

    fun confirm(alertId: Long) {
        val client = api ?: return
        val storage = store ?: return
        executor.execute {
            val text = try {
                val result = client.confirm(alertId, storage.deviceId())
                if (result.success) TEXT_CONFIRMED else result.message.ifEmpty { TEXT_ERROR }
            } catch (ignored: IOException) {
                null
            }
            main.post {
                text?.let { TrackerHub.notifyMessage(it) }
                sync()
            }
        }
    }

    private fun flushOnePending(client: AlertsApi, storage: AlertStore) {
        val report = storage.firstPending() ?: return
        if (System.currentTimeMillis() - lastSendMs < MIN_SEND_INTERVAL_MS) return
        val result = client.sendReport(report, storage.deviceId())
        lastSendMs = System.currentTimeMillis()
        storage.removeFirstPending()
        if (report.photoPath.isNotEmpty()) File(report.photoPath).delete()
        val text = when {
            result.success && result.confirmed -> TEXT_CONFIRMED
            result.success -> TEXT_SENT
            else -> result.message.ifEmpty { TEXT_ERROR }
        }
        main.post { TrackerHub.notifyMessage(text) }
    }

    private fun flushOneContact(client: AlertsApi, storage: AlertStore) {
        val contact = storage.firstContact() ?: return
        val result = client.sendContact(contact, storage.deviceId())
        storage.removeFirstContact()
        val text = if (result.success) TEXT_CONTACT_SENT else result.message.ifEmpty { TEXT_ERROR }
        main.post { TrackerHub.notifyMessage(text) }
    }
}
