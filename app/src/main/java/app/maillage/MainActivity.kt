package app.maillage

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Base64
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class Msg(val name: String, val text: String, val hops: Int, val me: Boolean, val time: Long, val chat: String = "")

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
    private lateinit var idV: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var awareCheck: CheckBox
    private lateinit var nostrCheck: CheckBox
    private lateinit var torCheck: CheckBox
    private lateinit var transportStatus: TextView
    private var torManager: TorManager? = null
    private var cur = ""
    private var shown: List<Msg> = emptyList()
    private var people: List<Person> = emptyList()
    private val unread = HashMap<String, Int>()
    private val names = HashMap<String, String>()
    private var launchSplash: LaunchSplash? = null
    private var splashReady = false
    private var activityResumed = false
    private var splashStatusColor = 0
    private var splashSystemUiVisibility = 0
    private fun dec(s: String?) = try { if (s.isNullOrEmpty()) "" else Vault.dec(s) } catch (e: Exception) { "" }

    private fun c(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(col: Int, r: Int) = GradientDrawable().apply { setColor(col); cornerRadius = dp(r).toFloat() }
    private fun LinearLayout.put(v: View, top: Int = 0) { addView(v, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(top) }) }
    private fun tv(s: String, sz: Float, col: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sz; setTextColor(col); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private val bubbles = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, p: ViewGroup): View {
            val m = shown[i]
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
            val meta = fmt.format(Date(m.time)) + (if (m.me) " · " + getString(R.string.unconfirmed) else " · " + (if (m.hops == 0) getString(R.string.hops_direct) else if (m.hops > 1) getString(R.string.hops_many, m.hops) else getString(R.string.hops_one, m.hops))) + (if (m.chat.isNotEmpty()) " · 🔒" else "")
            b.addView(tv(meta, 11f, if (m.me) 0xCCFFFFFF.toInt() else c(R.color.mute)).apply { gravity = Gravity.END })
            row.addView(b)
            return row
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { fitsSystemWindows = true }
        joinV = buildJoin(); chatV = buildChat().apply { visibility = View.GONE }
        root.addView(joinV); root.addView(chatV)
        joinV.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        chatV.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        splashStatusColor = window.statusBarColor
        splashSystemUiVisibility = window.decorView.systemUiVisibility
        window.statusBarColor = Color.rgb(22, 29, 50)
        window.decorView.systemUiVisibility = splashSystemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        val splash = LaunchSplash(this) {
            splashReady = true
            finishLaunchWhenResumed()
        }
        launchSplash = splash
        root.addView(splash, FrameLayout.LayoutParams(MP, MP))
        setContentView(root)
        splash.start()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        finishLaunchWhenResumed()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    private fun finishLaunchWhenResumed() {
        val splash = launchSplash ?: return
        if (!activityResumed || !splashReady || isFinishing || isDestroyed) return
        splashReady = false
        tryAutoRejoin()
        splash.fadeOut {
            (splash.parent as? ViewGroup)?.removeView(splash)
            if (launchSplash === splash) launchSplash = null
            joinV.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            chatV.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            window.statusBarColor = splashStatusColor
            window.decorView.systemUiVisibility = splashSystemUiVisibility
        }
    }

    private fun tryAutoRejoin() {
        // Only reuse a previously saved choice. Missing prerequisites fall back to the
        // original join screen without requesting permissions or enabling Bluetooth.
        val savedChannel = prefs.contains("name") && pwF.text.toString().length >= 8
        val permissionsGranted = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val bluetoothEnabled = if (permissionsGranted) {
            runCatching { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true }.getOrDefault(false)
        } else false
        if (!RejoinPolicy.shouldRejoin(savedChannel, permissionsGranted, bluetoothEnabled)) return
        runCatching { begin() }.onFailure {
            runCatching { mesh?.stop() }
            mesh = null
            runCatching { stopService(Intent(this, MeshKeepaliveService::class.java)) }
            runCatching { torManager?.stop() }; torManager = null
            chatV.visibility = View.GONE; joinV.visibility = View.VISIBLE
            toast(getString(R.string.rejoin_failed))
        }
    }

    private fun buildJoin(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        fun field(h: String, v: String) = EditText(this).apply {
            hint = h; setText(v); setSingleLine(); setTextColor(c(R.color.ink)); setHintTextColor(c(R.color.mute))
            background = round(c(R.color.surface), 14); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        nameF = field(getString(R.string.hint_nickname), prefs.getString("name", "") ?: "").apply { filters = arrayOf(InputFilter.LengthFilter(12)) }
        pwF = field(getString(R.string.hint_password), dec(prefs.getString("pw", null))).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT
        }
        awareCheck = CheckBox(this).apply {
            text = getString(R.string.opt_aware); isChecked = prefs.getBoolean("aware", true)
            setTextColor(c(R.color.ink)); isEnabled = packageManager.hasSystemFeature("android.hardware.wifi.aware")
            if (!isEnabled) isChecked = false
        }
        nostrCheck = CheckBox(this).apply {
            text = getString(R.string.opt_nostr); isChecked = prefs.getBoolean("nostr", false); setTextColor(c(R.color.ink))
        }
        torCheck = CheckBox(this).apply {
            text = getString(R.string.opt_tor); isChecked = prefs.getBoolean("tor", false); setTextColor(c(R.color.ink)); isEnabled = nostrCheck.isChecked
        }
        nostrCheck.setOnCheckedChangeListener { _, enabled ->
            torCheck.isEnabled = enabled
            if (!enabled) torCheck.isChecked = false
        }
        val go = tv(getString(R.string.join), 16f, c(R.color.onAccent), true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = round(c(R.color.accent), 14); setOnClickListener { tryJoin() }
        }
        l.put(tv(getString(R.string.app_name), 34f, c(R.color.ink), true))
        l.put(tv(getString(R.string.tagline), 15f, c(R.color.mute)), 6)
        l.put(nameF, 24); l.put(pwF, 10)
        l.put(awareCheck, 10); l.put(nostrCheck, 2); l.put(torCheck, 2)
        l.put(tv(getString(R.string.relay_privacy), 12f, c(R.color.mute)), 4)
        l.put(go, 16)
        l.put(tv(getString(R.string.selftest), 14f, c(R.color.accent)).apply { setOnClickListener { Thread { val r = SelfTest.run(); runOnUiThread { android.app.AlertDialog.Builder(this@MainActivity).setMessage(r).setPositiveButton(getString(R.string.ok), null).show() } }.start() } }, 18)
        l.put(tv(getString(R.string.same_password), 13f, c(R.color.mute)), 10)
        l.put(tv(getString(R.string.wipe_all), 13f, c(R.color.mute)).apply {
            setOnClickListener { confirmReset() }
        }, 22)
        return ScrollView(this).apply { isFillViewport = true; addView(l) }
    }

    private fun buildChat(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        bar.addView(tv(getString(R.string.app_name), 22f, c(R.color.ink), true), LinearLayout.LayoutParams(0, WC, 1f))
        pill = tv("", 13f, c(R.color.mute)).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(c(R.color.surface), 16) }
        bar.addView(pill)
        bar.addView(tv(getString(R.string.clear), 14f, c(R.color.mute)).apply { setPadding(dp(10), dp(6), 0, dp(6)); setOnClickListener { msgs.removeAll { it.chat == cur }; save(); refresh() } })
        bar.addView(tv(getString(R.string.quit), 14f, c(R.color.accent)).apply { setPadding(dp(14), dp(6), 0, dp(6)); setOnClickListener { leave() } })
        l.addView(bar)
        idV = tv("", 12f, c(R.color.mute)).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
            setOnClickListener { showFingerprint(null) }
        }
        l.addView(idV)
        l.addView(tv(getString(R.string.fp_help), 12f, c(R.color.mute)).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
        })
        l.addView(tv(getString(R.string.reset_app), 12f, c(R.color.mute)).apply {
            setPadding(dp(16), 0, dp(16), dp(4)); setOnClickListener { confirmReset() }
        })
        transportStatus = tv("", 12f, c(R.color.mute)).apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        l.addView(transportStatus)
        chipsRow = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        l.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) })
        val fl = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; dividerHeight = 0; clipToPadding = false; setPadding(0, dp(4), 0, dp(8))
            setSelector(ColorDrawable(Color.TRANSPARENT)); adapter = bubbles
        }
        val empty = tv(getString(R.string.empty_chat), 15f, c(R.color.mute)).apply { gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        fl.addView(list, FrameLayout.LayoutParams(MP, MP)); fl.addView(empty, FrameLayout.LayoutParams(MP, MP))
        list.emptyView = empty
        l.addView(fl, LinearLayout.LayoutParams(MP, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)) }
        input = EditText(this).apply {
            hint = getString(R.string.hint_message); setHintTextColor(c(R.color.mute)); setTextColor(c(R.color.ink)); maxLines = 4
            background = round(c(R.color.surface), 24); setPadding(dp(16), dp(10), dp(16), dp(10)); filters = arrayOf(InputFilter.LengthFilter(100))
        }
        row.addView(input, LinearLayout.LayoutParams(0, WC, 1f))
        row.addView(tv("↑", 22f, c(R.color.onAccent), true).apply { contentDescription = getString(R.string.send); gravity = Gravity.CENTER; background = round(c(R.color.accent), 24); setOnClickListener { send() } },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        l.addView(row)
        return l
    }

    private fun perms(): Array<String> {
        val requested = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            requested += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else requested += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (awareCheck.isChecked) {
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.NEARBY_WIFI_DEVICES
            else if (Build.VERSION.SDK_INT >= 31) requested += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return requested.distinct().toTypedArray()
    }

    private fun tryJoin() {
        if (pwF.text.toString().length < 8) return toast(getString(R.string.err_password_short))
        if (perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) begin() else requestPermissions(perms(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c != 1) return
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!bluetooth.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            toast(getString(R.string.err_bt_denied))
            return
        }
        if (awareCheck.isChecked) {
            val awarePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                                  else Manifest.permission.ACCESS_FINE_LOCATION
            if (checkSelfPermission(awarePermission) != PackageManager.PERMISSION_GRANTED) {
                awareCheck.isChecked = false
                toast(getString(R.string.aware_disabled))
            }
        }
        begin()
    }

    private fun begin() {
        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) return toast(getString(R.string.enable_bt))
        myName = nameF.text.toString().replace("#", "").trim().ifBlank { "Anon" + (100..999).random() }
        val pw = pwF.text.toString()
        prefs.edit().putString("name", myName).putString("pw", Vault.enc(pw))
            .putBoolean("aware", awareCheck.isChecked).putBoolean("nostr", nostrCheck.isChecked).putBoolean("tor", torCheck.isChecked).apply()
        if (nostrCheck.isChecked && torCheck.isChecked) {
            startJoined(pw, null, false)
            transportStatus.text = getString(R.string.tor_starting)
            val activeMesh = mesh ?: return
            val manager = TorManager(this); torManager = manager
            Thread {
                val port = runCatching { manager.startAndWait() }
                runOnUiThread {
                    if (mesh !== activeMesh) { manager.stop(); return@runOnUiThread }
                    if (port.isSuccess) {
                        activeMesh.enableNostr(nostrKey(), port.getOrNull())
                        transportStatus.text = getString(R.string.tor_ready)
                    } else {
                        manager.stop(); torManager = null
                        transportStatus.text = getString(R.string.tor_unavailable)
                    }
                }
            }.start()
        } else startJoined(pw, null, nostrCheck.isChecked)
    }

    private fun startJoined(pw: String, torPort: Int?, internetEnabled: Boolean) {
        load(pw.hashCode().toString())
        names.clear(); unread.clear(); msgs.filter { it.chat.isNotEmpty() && !it.me }.forEach { names[it.chat] = it.name }
        mesh?.stop()
        val secret = if (internetEnabled) nostrKey() else null
        val nextMesh = Mesh(this, myName, pw, noiseKey(),
            { n, t, h -> add(Msg(n, t, h, false, System.currentTimeMillis(), "")) },
            { tg, who, t, h -> names[tg] = who; add(Msg(who, t, h, false, System.currentTimeMillis(), tg)) },
            { p -> people = p; chips() },
            { setPeers(it) },
            enableWifiAware = awareCheck.isChecked,
            nostrSecretHex = secret,
            torSocksPort = torPort,
            onTransportStatus = { status -> runOnUiThread { transportStatus.text = status } }
        )
        mesh = nextMesh
        nextMesh.start()
        idV.text = getString(R.string.your_id, myName, mesh!!.tag)
        transportStatus.text = if (internetEnabled) getString(R.string.net_nostr) else getString(R.string.net_off)
        setPeers(0); cur = ""; refresh(); chips()
        joinV.visibility = View.GONE; chatV.visibility = View.VISIBLE
    }

    private fun leave() {
        mesh?.stop(); mesh = null
        torManager?.stop(); torManager = null
        chatV.visibility = View.GONE; joinV.visibility = View.VISIBLE
    }

    private fun confirmReset() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_title))
            .setMessage(getString(R.string.reset_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.reset_confirm)) { _, _ ->
                leave()
                msgs.clear(); people = emptyList(); names.clear(); unread.clear(); cur = ""
                val prefsCleared = prefs.edit().clear().commit()
                val identityRemoved = runCatching { Ident.reset() }.isSuccess
                val vaultRemoved = runCatching { Vault.reset() }.isSuccess
                nameF.setText(""); pwF.setText(""); refresh()
                toast(if (prefsCleared && identityRemoved && vaultRemoved) getString(R.string.reset_done) else getString(R.string.reset_partial))
            }.show()
    }

    private fun send() {
        val t = input.text.toString().trim()
        val now = System.currentTimeMillis()
        if (t.isEmpty() || now - lastSend < 800) return
        val ok = if (cur == "") mesh?.send(t) == true else mesh?.sendPrivate(cur, t) == true
        if (ok) { add(Msg(myName, t, 0, true, now, cur)); input.setText(""); lastSend = now }
        else {
            val state = if (cur.isNotEmpty()) mesh?.verificationState(cur) else null
            toast(when (state) {
                ContactVerification.CHANGED -> getString(R.string.err_identity_changed)
                ContactVerification.UNAVAILABLE -> getString(R.string.err_identity_unavailable)
                else -> if (cur == "") getString(R.string.err_send) else getString(R.string.err_send_private)
            })
        }
    }

    private fun setPeers(n: Int) {
        pill.text = if (n > 1) getString(R.string.peers_many, n) else getString(R.string.peers_one, n)
        pill.setTextColor(if (n > 0) c(R.color.ok) else c(R.color.mute))
    }

    private fun add(m: Msg) {
        msgs.add(m); save()
        if (m.chat != cur && !m.me) { unread[m.chat] = (unread[m.chat] ?: 0) + 1; chips() }
        refresh()
    }

    private fun refresh() {
        shown = msgs.filter { it.chat == cur }
        input.hint = if (cur == "") getString(R.string.hint_channel) else getString(R.string.hint_private)
        bubbles.notifyDataSetChanged(); list.setSelection(shown.size)
    }

    private fun showFingerprint(peerTag: String?) {
        val active = mesh ?: return
        val fingerprint = if (peerTag == null) {
            runCatching { active.ownFingerprint() }.getOrNull()
        } else active.peerFingerprint(peerTag)
        val label = if (peerTag == null) getString(R.string.fp_own) else getString(R.string.fp_of, names[peerTag] ?: peerTag)
        if (peerTag == null) {
            android.app.AlertDialog.Builder(this).setTitle(label)
                .setMessage(if (fingerprint == null) getString(R.string.fp_unavailable) else getString(R.string.fp_compare_hint, fingerprint))
                .setPositiveButton(getString(R.string.close), null).show()
            return
        }
        val state = active.verificationState(peerTag)
        val saved = active.savedPeerFingerprint(peerTag)
        val explanation = when (state) {
            ContactVerification.UNAVAILABLE -> getString(R.string.fp_state_unavailable)
            ContactVerification.CHANGED -> getString(R.string.fp_state_changed, saved ?: getString(R.string.unavailable), fingerprint ?: getString(R.string.unavailable))
            ContactVerification.VERIFIED -> getString(R.string.fp_state_verified, fingerprint)
            ContactVerification.UNVERIFIED -> getString(R.string.fp_state_unverified, fingerprint)
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle(label).setMessage(explanation).setPositiveButton(getString(R.string.close), null)
        when (state) {
            ContactVerification.UNVERIFIED -> if (fingerprint != null) dialog.setNeutralButton(getString(R.string.fp_compared)) { _, _ ->
                if (mesh === active && active.verifyPeer(peerTag, fingerprint)) {
                    toast(getString(R.string.fp_saved)); chips()
                } else toast(getString(R.string.fp_not_saved))
            }
            ContactVerification.VERIFIED, ContactVerification.CHANGED -> dialog.setNeutralButton(getString(R.string.unverify)) { _, _ ->
                android.app.AlertDialog.Builder(this).setTitle(getString(R.string.unverify_title))
                    .setMessage(getString(R.string.unverify_message))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.remove)) { _, _ ->
                        if (mesh === active && active.forgetPeerVerification(peerTag)) {
                            toast(getString(R.string.unverified_done)); chips()
                        } else toast(getString(R.string.unverify_failed))
                    }.show()
            }
            ContactVerification.UNAVAILABLE -> Unit
        }
        dialog.show()
    }

    private fun chips() {
        chipsRow.removeAllViews()
        fun chip(id: String, label: String) {
            val n = unread[id] ?: 0; val sel = id == cur
            chipsRow.addView(tv(label + if (n > 0) " ($n)" else "", 14f, if (sel) c(R.color.onAccent) else c(R.color.ink)).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7)); background = round(if (sel) c(R.color.accent) else c(R.color.surface), 18)
                setOnClickListener { cur = id; unread.remove(id); chips(); refresh() }
                if (id.isNotEmpty()) setOnLongClickListener { showFingerprint(id); true }
            }, LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(8) })
        }
        chip("", getString(R.string.channel))
        people.forEach {
            names[it.tag] = it.name + "#" + it.tag
            val mark = when (mesh?.verificationState(it.tag)) {
                ContactVerification.VERIFIED -> " ✓"
                ContactVerification.CHANGED -> " ⚠"
                else -> ""
            }
            chip(it.tag, it.name + "#" + it.tag + mark)
        }
        val on = people.map { it.tag }.toSet()
        names.keys.filter { it !in on && (msgs.any { m -> m.chat == it } || it == cur) }.forEach { chip(it, (names[it] ?: it) + " " + getString(R.string.offline_suffix)) }
    }

    private fun nostrKey(): String {
        val saved = dec(prefs.getString("nostr_key", null))
        if (saved.isNotEmpty()) return saved
        val key = NostrRelayTransport.newSecretHex()
        prefs.edit().putString("nostr_key", Vault.enc(key)).apply()
        return key
    }

    private fun noiseKey(): ByteArray {
        val s = dec(prefs.getString("nk", null))
        if (s.isNotEmpty()) return Base64.decode(s, Base64.NO_WRAP)
        val k = Dh.gen().first
        prefs.edit().putString("nk", Vault.enc(Base64.encodeToString(k, Base64.NO_WRAP))).apply()
        return k
    }

    private fun load(chan: String) {
        msgs.clear()
        if (dec(prefs.getString("chan", null)) == chan) {
            try {
                val a = JSONArray(dec(prefs.getString("hist", null)).ifEmpty { "[]" })
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); msgs.add(Msg(o.getString("n"), o.getString("t"), o.getInt("h"), o.getBoolean("m"), o.getLong("s"), o.optString("c"))) }
            } catch (e: Exception) { }
        } else prefs.edit().putString("chan", Vault.enc(chan)).putString("hist", Vault.enc("[]")).apply()
    }

    private fun save() {
        val a = JSONArray()
        msgs.takeLast(200).forEach { a.put(JSONObject().put("n", it.name).put("t", it.text).put("h", it.hops).put("m", it.me).put("s", it.time).put("c", it.chat)) }
        prefs.edit().putString("hist", Vault.enc(a.toString())).apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onBackPressed() { if (chatV.visibility == View.VISIBLE) moveTaskToBack(true) else super.onBackPressed() }
    override fun onDestroy() {
        launchSplash?.dispose()
        launchSplash = null
        mesh?.stop(); torManager?.stop()
        super.onDestroy()
    }
}
