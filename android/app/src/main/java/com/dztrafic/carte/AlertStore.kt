package com.dztrafic.carte

import android.content.Context
import org.json.JSONArray
import java.util.UUID

private const val PREFS_NAME = "dz_store"
private const val KEY_DEVICE = "device_id"
private const val KEY_ALERTS = "alerts"
private const val KEY_ALERTS_SAVED_AT = "alerts_saved_at"
private const val KEY_PENDING = "pending_reports"
private const val KEY_CONTACTS = "pending_contacts"

class AlertStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        val random = UUID.randomUUID().toString().replace("-", "").take(13)
        val id = "dz_${System.currentTimeMillis()}_$random"
        prefs.edit().putString(KEY_DEVICE, id).apply()
        return id
    }

    @Synchronized
    fun saveAlerts(alerts: List<Alert>) {
        val array = JSONArray()
        alerts.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_ALERTS, array.toString())
            .putLong(KEY_ALERTS_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    @Synchronized
    fun loadAlerts(): List<Alert> {
        val text = prefs.getString(KEY_ALERTS, null) ?: return emptyList()
        val elapsedSeconds = (System.currentTimeMillis() - prefs.getLong(KEY_ALERTS_SAVED_AT, 0)) / 1000
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val alert = Alert.fromJson(array.getJSONObject(index))
                alert.copy(ageSeconds = alert.ageSeconds + elapsedSeconds)
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addPending(report: PendingReport) {
        val reports = readPending()
        reports.add(report)
        writePending(reports)
    }

    @Synchronized
    fun firstPending(): PendingReport? = readPending().firstOrNull()

    @Synchronized
    fun removeFirstPending() {
        val reports = readPending()
        if (reports.isNotEmpty()) {
            reports.removeAt(0)
            writePending(reports)
        }
    }

    @Synchronized
    fun addContact(contact: PendingContact) {
        val list = readContacts()
        list.add(contact)
        writeContacts(list)
    }

    @Synchronized
    fun firstContact(): PendingContact? = readContacts().firstOrNull()

    @Synchronized
    fun removeFirstContact() {
        val list = readContacts()
        if (list.isNotEmpty()) {
            list.removeAt(0)
            writeContacts(list)
        }
    }

    private fun readContacts(): MutableList<PendingContact> {
        val text = prefs.getString(KEY_CONTACTS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { PendingContact.fromJson(array.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeContacts(list: List<PendingContact>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    private fun readPending(): MutableList<PendingReport> {
        val text = prefs.getString(KEY_PENDING, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { PendingReport.fromJson(array.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writePending(reports: List<PendingReport>) {
        val array = JSONArray()
        reports.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PENDING, array.toString()).apply()
    }
}
