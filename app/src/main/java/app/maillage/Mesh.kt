package app.maillage

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

val SVC: UUID = UUID.fromString("7f3a1c10-5b2e-4c8a-9d11-6a0e4f2b9c01")
val CHR: UUID = UUID.fromString("7f3a1c11-5b2e-4c8a-9d11-6a0e4f2b9c01")
const val TTL0 = 7

/** Maillage BLE : chaque téléphone est serveur GATT + client des voisins, et relaie (flooding, TTL, anti-doublons). */
@SuppressLint("MissingPermission")
class Mesh(
    private val ctx: Context, private val name: String, password: String,
    private val onMsg: (name: String, text: String, hops: Int) -> Unit,
    private val onPeers: (Int) -> Unit
) {
    private val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val h = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()
    private val key: SecretKeySpec = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), "maillage-v1".toByteArray(), 60000, 256)).encoded, "AES")
    private val seen = LinkedHashSet<String>()
    private val store = ArrayDeque<Pair<Long, ByteArray>>()   // messages conservés 1 h, rejoués aux nouveaux voisins
    private val peers = HashMap<String, Peer>()
    private val connecting = HashSet<String>()
    private var server: BluetoothGattServer? = null

    private val mtus = HashMap<String, Int>()

    inner class Peer(val g: BluetoothGatt, val c: BluetoothGattCharacteristic, val mtu: Int) {
        val q = ArrayDeque<ByteArray>(); var busy = false
    }

    fun start() {
        val srv = mgr.openGattServer(ctx, srvCb); server = srv
        val s = BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        s.addCharacteristic(BluetoothGattCharacteristic(CHR, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        srv.addService(s)
        mgr.adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SVC)).setIncludeDeviceName(false).build(), advCb)
        mgr.adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
    }

    fun stop() {
        mgr.adapter.bluetoothLeAdvertiser?.stopAdvertising(advCb)
        mgr.adapter.bluetoothLeScanner?.stopScan(scanCb)
        peers.values.forEach { it.g.close() }; peers.clear(); connecting.clear(); mtus.clear(); server?.close()
    }

    fun send(text: String): Boolean {
        val id = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val j = JSONObject().put("i", id).put("t", TTL0).put("n", name)
            .put("s", System.currentTimeMillis()).put("p", enc(text))
        seen.add(id)
        val b = j.toString().toByteArray()
        if (b.size > 500) return false
        keep(b); relay(b, null)
        return true
    }

    private fun enc(t: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(t.toByteArray()), Base64.NO_WRAP)
    }

    private fun dec(p: String): String {
        val r = Base64.decode(p, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, r, 0, 12))
        return String(c.doFinal(r, 12, r.size - 12))
    }

    private fun keep(b: ByteArray) {
        val now = System.currentTimeMillis()
        store.addLast(now to b)
        while (store.size > 60 || (store.isNotEmpty() && now - store.first().first > 3_600_000)) store.removeFirst()
    }

    private fun relay(b: ByteArray, except: String?) {
        peers.forEach { (a, p) -> if (a != except) { p.q.addLast(b); pump(p) } }
    }

    private fun pump(p: Peer) {
        while (!p.busy && p.q.isNotEmpty()) {
            val b = p.q.removeFirst()
            if (b.size > p.mtu - 3) continue
            p.c.value = b
            p.c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            p.busy = p.g.writeCharacteristic(p.c)
        }
    }

    private fun receive(b: ByteArray, from: String) {
        val j = try { JSONObject(String(b)) } catch (e: Exception) { return }
        val id = j.optString("i"); if (id.isEmpty() || !seen.add(id)) return
        if (seen.size > 2000) seen.remove(seen.first())
        val ttl = j.optInt("t")
        try { onMsg(j.getString("n"), dec(j.getString("p")), TTL0 - ttl) } catch (e: Exception) { /* autre canal : on relaie sans lire */ }
        if (ttl > 1) {
            j.put("t", ttl - 1)
            val out = j.toString().toByteArray()
            keep(out); relay(out, from)
        }
    }

    private val srvCb = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(d: BluetoothDevice, req: Int, c: BluetoothGattCharacteristic,
                                                  prep: Boolean, resp: Boolean, off: Int, v: ByteArray) {
            if (resp) server?.sendResponse(d, req, BluetoothGatt.GATT_SUCCESS, 0, null)
            h.post { receive(v, d.address) }
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) {
            h.post {
                val a = r.device.address
                if (a in peers || !connecting.add(a)) return@post
                val gatt = r.device.connectGatt(ctx, false, cliCb, BluetoothDevice.TRANSPORT_LE)
                h.postDelayed({ if (a !in peers && connecting.remove(a)) gatt.close() }, 15000)
            }
        }
    }

    private val advCb = object : AdvertiseCallback() {}

    private val cliCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) g.requestMtu(512)
            else h.post { peers.remove(g.device.address); connecting.remove(g.device.address); g.close(); onPeers(peers.size) }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            h.post { mtus[g.device.address] = if (st == BluetoothGatt.GATT_SUCCESS) mtu else 23 }
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val c = g.getService(SVC)?.getCharacteristic(CHR)
            h.post {
                if (c == null || peers.size >= 6) { g.disconnect(); return@post }
                val p = Peer(g, c, mtus[g.device.address] ?: 23); peers[g.device.address] = p; connecting.remove(g.device.address)
                store.forEach { p.q.addLast(it.second) }; pump(p); onPeers(peers.size)
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            h.post { peers[g.device.address]?.let { it.busy = false; pump(it) } }
        }
    }
}
