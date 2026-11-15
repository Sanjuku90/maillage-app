package app.maillage

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class Msg(val name: String, val text: String, val hops: Int, val me: Boolean, val time: Long)

class MainActivity : Activity() {
    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    private var mesh: Mesh? = null
    private val msgs = ArrayList<Msg>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val prefs by lazy { getSharedPreferences("maillage", MODE_PRIVATE) }
    private var lastSend = 0L
    private var myName = ""
    private lateinit var joinV: View
    private lateinit var chatV: View
    private lateinit var list: ListView
    private lateinit var pill: TextView
    private lateinit var nameF: EditText
    private lateinit var pwF: EditText
    private lateinit var input: EditText

    private fun c(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(col: Int, r: Int) = GradientDrawable().apply { setColor(col); cornerRadius = dp(r).toFloat() }
    private fun LinearLayout.put(v: View, top: Int = 0) { addView(v, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(top) }) }
    private fun tv(s: String, sz: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sz; setTextColor(col); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private val bubbles = object : BaseAdapter() {
        override fun getCount() = msgs.size
        override fun getItem(i: Int) = msgs[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, p: ViewGroup): View {
            val m = msgs[i]
            val maxW = (resources.displayMetrics.widthPixels * 0.72).toInt()
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(3), dp(12), dp(3))
                gravity = if (m.me) Gravity.END else Gravity.START
            }
            val b = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(7))
                background = round(if (m.me) c(R.color.accent) else c(R.color.bubbleIn), 16)
            }
            if (!m.me) b.addView(tv(m.name, 12f, Color.HSVToColor(floatArrayOf(((m.name.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.75f)), true).apply { maxWidth = maxW })
            b.addView(tv(m.text, 16f, if (m.me) c(R.color.onAccent) else c(R.color.ink)).apply { maxWidth = maxW })
            val meta = fmt.format(Date(m.time)) + if (m.me) "" else " · " + (if (m.hops == 0) "direct" else "${m.hops} saut${if (m.hops > 1) "s" else ""}")
            b.addView(tv(meta, 11f, if (m.me) 0xCCFFFFFF.toInt() else c(R.color.mute)).apply { gravity = Gravity.END })
            row.addView(b)
            return row
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(this).apply { fitsSystemWindows = true }
        joinV = buildJoin(); chatV = buildChat().apply { visibility = View.GONE }
        root.addView(joinV); root.addView(chatV)
        setContentView(root)
    }

    private fun buildJoin(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        fun field(h: String, v: String) = EditText(this).apply {
            hint = h; setText(v); setSingleLine(); setTextColor(c(R.color.ink)); setHintTextColor(c(R.color.mute))
            background = round(c(R.color.surface), 14); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        nameF = field("Ton pseudo", prefs.getString("name", "") ?: "")
        pwF = field("Mot de passe du canal", prefs.getString("pw", "") ?: "")
        val go = tv("Rejoindre le maillage", 16f, c(R.color.onAccent), true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = round(c(R.color.accent), 14); setOnClickListener { tryJoin() }
        }
        l.put(tv("Maillage", 34f, c(R.color.ink), true))
        l.put(tv("Messagerie sans internet, de téléphone en téléphone par Bluetooth.", 15f, c(R.color.mute)), 6)
        l.put(nameF, 32); l.put(pwF, 12); l.put(go, 20)
        l.put(tv("Tous les téléphones du groupe doivent utiliser le même mot de passe. Il chiffre les messages.", 13f, c(R.color.mute)), 14)
        return l
    }

    private fun buildChat(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        bar.addView(tv("Maillage", 22f, c(R.color.ink), true), LinearLayout.LayoutParams(0, WC, 1f))
        pill = tv("", 13f, c(R.color.mute)).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(c(R.color.surface), 16) }
        bar.addView(pill)
        bar.addView(tv("Quitter", 14f, c(R.color.accent)).apply { setPadding(dp(14), dp(6), 0, dp(6)); setOnClickListener { leave() } })
        l.addView(bar)
        val fl = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; dividerHeight = 0; clipToPadding = false; setPadding(0, dp(4), 0, dp(8))
            setSelector(ColorDrawable(Color.TRANSPARENT)); adapter = bubbles
        }
        val empty = tv("Aucun message pour l’instant.\nÉcris le premier : il saute de téléphone en téléphone.", 15f, c(R.color.mute)).apply { gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        fl.addView(list, FrameLayout.LayoutParams(MP, MP)); fl.addView(empty, FrameLayout.LayoutParams(MP, MP))
        list.emptyView = empty
        l.addView(fl, LinearLayout.LayoutParams(MP, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)) }
        input = EditText(this).apply {
            hint = "Message"; setHintTextColor(c(R.color.mute)); setTextColor(c(R.color.ink)); maxLines = 4
            background = round(c(R.color.surface), 24); setPadding(dp(16), dp(10), dp(16), dp(10)); filters = arrayOf(InputFilter.LengthFilter(200))
        }
        row.addView(input, LinearLayout.LayoutParams(0, WC, 1f))
        row.addView(tv("↑", 22f, c(R.color.onAccent), true).apply { gravity = Gravity.CENTER; background = round(c(R.color.accent), 24); setOnClickListener { send() } },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        l.addView(row)
        return l
    }

    private fun perms() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun tryJoin() {
        if (pwF.text.toString().length < 4) return toast("Mot de passe : 4 caractères minimum")
        if (perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) begin() else requestPermissions(perms(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) begin() else toast("Permissions Bluetooth refusées")
    }

    private fun begin() {
        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) return toast("Active le Bluetooth")
        myName = nameF.text.toString().trim().ifBlank { "Anon" + (100..999).random() }
        val pw = pwF.text.toString()
        prefs.edit().putString("name", myName).putString("pw", pw).apply()
        load(pw.hashCode().toString()); bubbles.notifyDataSetChanged(); list.setSelection(msgs.size)
        mesh?.stop()
        mesh = Mesh(this, myName, pw, { n, t, h -> add(Msg(n, t, h, false, System.currentTimeMillis())) }, { setPeers(it) }).also { it.start() }
        setPeers(0)
        joinV.visibility = View.GONE; chatV.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun leave() {
        mesh?.stop(); mesh = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chatV.visibility = View.GONE; joinV.visibility = View.VISIBLE
    }

    private fun send() {
        val t = input.text.toString().trim()
        val now = System.currentTimeMillis()
        if (t.isEmpty() || now - lastSend < 800) return
        if (mesh?.send(t) == true) { add(Msg(myName, t, 0, true, now)); input.setText(""); lastSend = now }
        else toast("Message trop long")
    }

    private fun setPeers(n: Int) {
        pill.text = "● $n voisin" + if (n > 1) "s" else ""
        pill.setTextColor(if (n > 0) c(R.color.ok) else c(R.color.mute))
    }

    private fun add(m: Msg) { msgs.add(m); save(); bubbles.notifyDataSetChanged(); list.setSelection(msgs.size - 1) }

    private fun load(chan: String) {
        msgs.clear()
        if (prefs.getString("chan", "") == chan) {
            try {
                val a = JSONArray(prefs.getString("hist", "[]"))
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); msgs.add(Msg(o.getString("n"), o.getString("t"), o.getInt("h"), o.getBoolean("m"), o.getLong("s"))) }
            } catch (e: Exception) { }
        } else prefs.edit().putString("chan", chan).putString("hist", "[]").apply()
    }

    private fun save() {
        val a = JSONArray()
        msgs.takeLast(200).forEach { a.put(JSONObject().put("n", it.name).put("t", it.text).put("h", it.hops).put("m", it.me).put("s", it.time)) }
        prefs.edit().putString("hist", a.toString()).apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onBackPressed() { if (chatV.visibility == View.VISIBLE) moveTaskToBack(true) else super.onBackPressed() }
    override fun onDestroy() { mesh?.stop(); super.onDestroy() }
}
