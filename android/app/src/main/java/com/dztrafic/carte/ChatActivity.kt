package com.dztrafic.carte

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran du chat entre voyageurs (Bluetooth, sans internet). */
class ChatActivity : Activity(), ChatHub.Listener {

    private companion object {
        const val PERMISSION_REQUEST = 7
        const val MIN_SEND_INTERVAL_MS = 5_000L
        const val SEND_SIZE_DP = 50
        val MINE = Color.rgb(212, 230, 252)
        val OTHER = Color.WHITE
    }

    private val arabic by lazy { isArabicUi() }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private var messages: List<ChatMessage> = emptyList()
    private var replyTo: ChatMessage? = null
    private var lastSendMs = 0L

    private lateinit var status: TextView
    private lateinit var statusAction: Button
    private lateinit var list: ListView
    private lateinit var replyBar: LinearLayout
    private lateinit var replyText: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    private val adapter = object : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = bubble(messages[position])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChatHub.init(this)
        val root = buildLayout()
        setContentView(root)
        fitSystemBars(root, withKeyboard = true)
        if (!ChatHub.hasPermissions(this)) requestPermissions(ChatHub.requiredPermissions(), PERMISSION_REQUEST)
    }

    override fun onStart() {
        super.onStart()
        ChatHub.add(this)
        ChatHub.acquire(this)
        ChatHub.viewing = true
        reload()
    }

    override fun onResume() {
        super.onResume()
        ChatHub.refresh()
        updateStatus()
    }

    override fun onStop() {
        ChatHub.viewing = false
        ChatHub.remove(this)
        ChatHub.release()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ChatHub.refresh()
        updateStatus()
    }

    override fun onChatChanged() = reload()

    override fun onNeighbours(count: Int) = updateStatus()

    private fun text(ar: String, fr: String) = if (arabic) ar else fr

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(232, 240, 250))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(18), dp(16), dp(18), dp(12))
            elevation = dp(4).toFloat()
        }
        header.addView(TextView(this).apply {
            text = text("دردشة المسافرين", "Chat des voyageurs")
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
        })
        header.addView(TextView(this).apply {
            text = text(
                "بدون إنترنت، عبر البلوتوث حتى 100 م",
                "Sans internet, par Bluetooth jusqu'à 100 m",
            )
            textSize = 14f
            setTextColor(TEXT_SOFT)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        status = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ROUTE_BLUE)
            gravity = Gravity.CENTER
        }
        statusAction = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(ROUTE_BLUE, 14)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            setOnClickListener { fixBluetooth() }
        }
        statusRow.addView(status)
        statusRow.addView(statusAction, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(10) })
        header.addView(statusRow)
        root.addView(header)

        list = ListView(this).apply {
            adapter = this@ChatActivity.adapter
            divider = null
            dividerHeight = 0
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            isStackFromBottom = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ -> messageOptions(messages[position]) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        replyText = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT_SOFT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(220, 232, 247))
            setPadding(dp(14), dp(6), dp(6), dp(6))
            visibility = View.GONE
            addView(replyText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@ChatActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(TEXT_SOFT)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { setReply(null) }
            })
        }
        root.addView(replyBar)

        input = EditText(this).apply {
            hint = text("اكتب رسالة", "Écrire un message")
            textSize = 16f
            setTextColor(TEXT_DARK)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(ChatMessage.MAX_TEXT))
            maxLines = 4
            // Même hauteur que le bouton d'envoi tant que le message tient sur une ligne.
            minHeight = dp(SEND_SIZE_DP)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = rounded(Color.rgb(246, 248, 247), 18, Color.rgb(210, 220, 214))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        sendButton = Button(this).apply {
            text = "➤"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = oval(ORANGE, Color.WHITE, 2)
            minWidth = 0
            minHeight = 0
            setOnClickListener { sendMessage() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(SEND_SIZE_DP), dp(SEND_SIZE_DP)).apply { marginStart = dp(8) })
        }
        root.addView(inputRow)
        return root
    }

    private fun reload() {
        messages = ChatHub.store?.visible() ?: emptyList()
        adapter.notifyDataSetChanged()
    }

    private fun updateStatus() {
        val permitted = ChatHub.hasPermissions(this)
        val on = ChatHub.bluetoothOn(this)
        val ready = permitted && on
        status.text = when {
            !permitted -> text("البلوتوث غير مسموح", "Bluetooth non autorisé")
            !on -> text("البلوتوث متوقف", "Bluetooth désactivé")
            ChatHub.neighbourCount == 0 -> text("🔎 البحث عن مسافرين قريبين…", "🔎 Recherche de voyageurs proches…")
            else -> text("👤 المسافرون: ${ChatHub.neighbourCount}", "👤 Voyageurs : ${ChatHub.neighbourCount}")
        }
        statusAction.visibility = if (ready) View.GONE else View.VISIBLE
        statusAction.text = if (!permitted) text("السماح", "Autoriser") else text("تفعيل", "Activer")
        input.isEnabled = ready
        sendButton.isEnabled = ready
        sendButton.alpha = if (ready) 1f else 0.4f
    }

    private fun fixBluetooth() {
        if (!ChatHub.hasPermissions(this)) {
            requestPermissions(ChatHub.requiredPermissions(), PERMISSION_REQUEST)
            return
        }
        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSendMs < MIN_SEND_INTERVAL_MS) {
            Toast.makeText(this, text("انتظر قليلاً", "Patiente un peu"), Toast.LENGTH_SHORT).show()
            return
        }
        lastSendMs = now
        ChatHub.send(message, replyTo?.id.orEmpty())
        input.setText("")
        setReply(null)
    }

    private fun setReply(message: ChatMessage?) {
        replyTo = message
        replyBar.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) replyText.text = "↩ ${ChatHub.displayName(message.author, arabic)} : ${message.text}"
    }

    private fun messageOptions(message: ChatMessage) {
        val mine = message.author == ChatHub.store?.author
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (!mine) {
            labels.add(text("↩ الرد", "↩ Répondre"))
            actions.add { setReply(message) }
        }
        labels.add(text("🙈 إخفاء", "🙈 Masquer"))
        actions.add { ChatHub.hide(message.id) }
        if (!mine) {
            labels.add(text("🚩 التبليغ عن الرسالة", "🚩 Signaler ce message"))
            actions.add {
                ChatHub.report(message.id)
                Toast.makeText(this, text("تم التبليغ، شكراً", "Message signalé, merci"), Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun bubble(message: ChatMessage): View {
        val mine = message.author == ChatHub.store?.author
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (mine) MINE else OTHER, 16, Color.rgb(208, 221, 238))
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "${if (mine) text("أنا", "Moi") else ChatHub.displayName(message.author, arabic)} · ${timeFormat.format(Date(message.sentAt))}"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (mine) Color.rgb(13, 71, 161) else ROUTE_BLUE)
        })
        if (message.replyTo.isNotEmpty()) {
            ChatHub.store?.find(message.replyTo)?.let { original ->
                box.addView(TextView(this).apply {
                    text = "↩ ${ChatHub.displayName(original.author, arabic)} : ${original.text}"
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(TEXT_SOFT)
                    background = rounded(Color.rgb(232, 240, 250), 10)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            }
        }
        box.addView(TextView(this).apply {
            text = message.text
            textSize = 16f
            setTextColor(TEXT_DARK)
            setPadding(0, dp(4), 0, 0)
        })
        return FrameLayout(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(box, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                if (mine) Gravity.END else Gravity.START,
            ).apply {
                if (mine) marginStart = dp(48) else marginEnd = dp(48)
            })
        }
    }
}
