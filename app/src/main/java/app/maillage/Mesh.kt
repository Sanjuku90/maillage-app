package app.maillage

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

val SVC: UUID = UUID.fromString("7f3a1c10-5b2e-4c8a-9d11-6a0e4f2b9c01")
val CHR: UUID = UUID.fromString("7f3a1c11-5b2e-4c8a-9d11-6a0e4f2b9c01")
const val TTL0 = 16
private const val MAX_SEEN_IDS = 2_000
private const val MAX_QUEUED_FRAMES_PER_PEER = 128
private const val MAX_TRACKED_SENDERS = 64
private const val MAX_FRAGMENTS_PER_FRAME = 64

class Person(val tag: String, val name: String, val pub: ByteArray, var seen: Long)
private class Sess(var hs: Handshake?, var tr: Transport?, val init: Boolean)

/** Maillage BLE : flooding + TTL + anti-doublons. Canal chiffré (Argon2id + AES-GCM), messages signés, messages privés Noise XX. */
@SuppressLint("MissingPermission")
class Mesh(
    private val ctx: Context, private val name: String, password: String, private val noisePriv: ByteArray,
    private val onMsg: (String, String, Int) -> Unit,
    private val onPrivate: (String, String, String, Int) -> Unit,
    private val onPeople: (List<Person>) -> Unit,
    private val onPeers: (Int) -> Unit,
    private val enableWifiAware: Boolean = false,
    private val nostrSecretHex: String? = null,
    private val torSocksPort: Int? = null,
    private val onTransportStatus: (String) -> Unit = {}
) {
    private val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val h = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()
    private val channelKey = Kdf.argon2id(password)
    private val key: SecretKeySpec = SecretKeySpec(channelKey, "AES")
    private val channelTag = MessageDigest.getInstance("SHA-256").digest(channelKey).take(8).joinToString("") { "%02x".format(it) }
    private val localAwareId = MessageDigest.getInstance("SHA-256").digest(Ident.pub()).copyOfRange(0, 8)
    private val noisePub = Dh.pub(noisePriv)
    val tag = Ident.tag(Ident.pub())
    private val tagBytes = unhex(tag)
    private val rate = LinkedHashMap<String, ArrayDeque<Long>>()
    private val bleFragments = LinkedHashMap<String, LinkedHashMap<Int, BleAssembly>>()
    private val fragmentSequence = AtomicInteger(1)
    private var wifiAware: WifiAwareTransport? = null
    private var nostr: NostrRelayTransport? = null
    private val seen = LinkedHashSet<String>()
    // A short tag is only a routing hint, not proof of identity. Pin the full key
    // for the lifetime of this mesh session and reject ambiguous short-tag collisions.
    private val identityPins = IdentityPins(MAX_SEEN_IDS)
    private val store = ArrayDeque<Pair<Long, ByteArray>>()
    private val peers = HashMap<String, Peer>()
    private val connecting = HashSet<String>()
    private val mtus = HashMap<String, Int>()
    private val people = HashMap<String, Person>()
    private val sess = HashMap<String, Sess>()
    private val pending = HashMap<String, ArrayList<String>>()
    private var server: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribers = LinkedHashMap<String, BluetoothDevice>()
    private val serverMtu = HashMap<String, Int>()
    private val serverQueue = ArrayDeque<ServerNotification>()
    private var serverInFlight: ServerNotification? = null

    private class ServerNotification(val address: String, val bytes: ByteArray)
    private class BleAssembly(val count: Int, val parts: Array<ByteArray?>, val created: Long, var bytes: Int = 0)

    inner class Peer(val g: BluetoothGatt, val c: BluetoothGattCharacteristic, val mtu: Int) {
        val q = ArrayDeque<ByteArray>(); var busy = false
    }

    private val tick = object : Runnable {
        override fun run() {
            announce()
            val now = System.currentTimeMillis()
            if (people.values.removeAll { now - it.seen > 100_000 }) onPeople(people.values.toList())
            h.postDelayed(this, 30_000)
        }
    }

    fun start() {
        val keepalive = Intent(ctx, MeshKeepaliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(keepalive) else ctx.startService(keepalive)
        val srv = mgr.openGattServer(ctx, srvCb); server = srv
        val s = BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(CHR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        characteristic.addDescriptor(BluetoothGattDescriptor(CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        s.addCharacteristic(characteristic)
        serverCharacteristic = characteristic
        srv.addService(s)
        mgr.adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SVC)).setIncludeDeviceName(false).build(), advCb)
        mgr.adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        if (enableWifiAware) {
            wifiAware = WifiAwareTransport(ctx, localAwareId, channelTag,
                { peer, frame -> h.post { receive(frame, peer, "aware") } },
                { status -> h.post { onTransportStatus(status) } }).also { it.start() }
        }
        if (nostrSecretHex != null) {
            nostr = runCatching {
                NostrRelayTransport(nostrSecretHex, channelTag, torSocksPort,
                    { frame -> h.post { receive(frame, "nostr", "nostr") } },
                    { status -> h.post { onTransportStatus(status) } }).also { it.start() }
            }.onFailure { onTransportStatus("Relais Nostr indisponible: clé invalide") }.getOrNull()
        }
        h.postDelayed(tick, 3000)
    }

    fun enableNostr(secretHex: String, socksPort: Int? = null) {
        if (nostr != null) return
        nostr = runCatching {
            NostrRelayTransport(secretHex, channelTag, socksPort,
                { frame -> h.post { receive(frame, "nostr", "nostr") } },
                { status -> h.post { onTransportStatus(status) } }).also { it.start() }
        }.onFailure { h.post { onTransportStatus("Relais Nostr indisponible: clé invalide") } }.getOrNull()
    }

    fun stop() {
        h.removeCallbacksAndMessages(null)
        mgr.adapter.bluetoothLeAdvertiser?.stopAdvertising(advCb)
        mgr.adapter.bluetoothLeScanner?.stopScan(scanCb)
        peers.values.forEach { it.g.close() }; peers.clear(); connecting.clear(); mtus.clear(); server?.close()
        wifiAware?.stop(); wifiAware = null
        nostr?.stop(); nostr = null
        bleFragments.clear(); rate.clear(); seen.clear(); identityPins.clear()
        pending.clear(); sess.clear(); people.clear(); store.clear()
        noisePriv.fill(0); channelKey.fill(0)
        subscribers.clear(); serverMtu.clear(); serverQueue.clear(); serverInFlight = null; serverCharacteristic = null
        ctx.stopService(Intent(ctx, MeshKeepaliveService::class.java))
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun clean(s: String) = s.replace(Regex("\\p{Cntrl}"), " ").take(200)
    private fun sd(id: String, ts: Long, n: ByteArray, t: ByteArray) =
        id.toByteArray() + Ident.ts(ts) + byteArrayOf(n.size.toByte()) + n + t

    /** Enveloppe commune : chiffrée avec la clé du canal, signée par l'identité de l'appareil. type 0=message, 1=annonce, 2=privé. */
    private fun emit(type: Int, data: ByteArray): Boolean {
        val id = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ts = System.currentTimeMillis()
        val nb = name.toByteArray(); val tb = byteArrayOf(type.toByte()) + data
        val sig = Ident.sign(sd(id, ts, nb, tb))
        val plain = ByteArrayOutputStream().apply {
            write(nb.size); write(nb); write(Ident.pub()); write(Ident.ts(ts)); write(sig.size); write(sig); write(tb)
        }.toByteArray()
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(id.toByteArray())
        val p = Base64.encodeToString(iv + c.doFinal(plain), Base64.NO_WRAP)
        val b = JSONObject().put("i", id).put("t", TTL0).put("p", p).toString().toByteArray()
        if (b.size > 500) return false
        remember(id); if (type == 0) keep(b); relay(b, null)
        return true
    }

    fun send(text: String) = emit(0, text.toByteArray())
    private fun announce() { emit(1, noisePub) }

    // ---- messages privés (Noise XX) ----
    fun sendPrivate(to: String, text: String): Boolean {
        if (text.toByteArray().size > 100 || people[to] == null) return false
        val tr = sess[to]?.tr
        if (tr != null) return frame(to, 4, tr.enc(text.toByteArray()))
        if (pending.values.sumOf { it.size } >= 200) return false
        val queued = pending.getOrPut(to) { ArrayList() }
        if (queued.size >= 20) return false
        queued.add(text)
        if (sess[to] == null) {
            val hs = Handshake(true, noisePriv, noisePub)
            val session = Sess(hs, null, true)
            sess[to] = session
            if (!frame(to, 1, hs.write(ByteArray(0)))) {
                sess.remove(to); pending.remove(to)
                return false
            }
            h.postDelayed({
                if (sess[to] === session && session.tr == null) {
                    sess.remove(to)
                    if (pending.remove(to) != null) onTransportStatus("Message privé non transmis : contact inaccessible")
                }
            }, 20_000)
        }
        return true
    }

    private fun frame(to: String, stage: Int, data: ByteArray) = emit(2, unhex(to) + byteArrayOf(stage.toByte()) + data)

    private fun flush(to: String) {
        val tr = sess[to]?.tr ?: return
        pending.remove(to)?.forEach {
            if (!frame(to, 4, tr.enc(it.toByteArray()))) onTransportStatus("Message privé non transmis")
        }
    }

    private fun priv(from: String, who: String, stage: Int, data: ByteArray, hops: Int) {
        val person = people[from]
        try {
            when (stage) {
                1 -> {
                    if (person == null) return
                    val ex = sess[from]
                    if (ex != null && ex.init && tag < from) return
                    val hs = Handshake(false, noisePriv, noisePub); hs.read(data)
                    sess[from] = Sess(hs, null, false); frame(from, 2, hs.write(ByteArray(0)))
                }
                2 -> {
                    val s = sess[from] ?: return; val hs = s.hs ?: return
                    if (!s.init) return
                    hs.read(data)
                    if (!hs.rs.contentEquals(person?.pub ?: return)) {
                        sess.remove(from)
                        if (pending.remove(from) != null) onTransportStatus("Identité privée incohérente : envoi annulé")
                        return
                    }
                    frame(from, 3, hs.write(ByteArray(0))); s.tr = hs.split(); s.hs = null; flush(from)
                }
                3 -> {
                    val s = sess[from] ?: return; val hs = s.hs ?: return
                    if (s.init) return
                    hs.read(data)
                    if (!hs.rs.contentEquals(person?.pub ?: return)) {
                        sess.remove(from)
                        if (pending.remove(from) != null) onTransportStatus("Identité privée incohérente : envoi annulé")
                        return
                    }
                    s.tr = hs.split(); s.hs = null
                }
                4 -> {
                    val tr = sess[from]?.tr
                    if (tr == null) { frame(from, 5, ByteArray(0)); return }
                    onPrivate(from, who, clean(String(tr.dec(data))), hops)
                }
                5 -> sess.remove(from)
            }
        } catch (e: Exception) {
            sess.remove(from)
            if (pending.remove(from) != null) onTransportStatus("Message privé non transmis : session interrompue")
            if (stage == 4) frame(from, 5, ByteArray(0))
        }
    }

    private class Opened(val name: String, val body: ByteArray, val ts: Long, val tag: String, val signingKey: ByteArray)

    /** Déchiffre puis vérifie la signature. Tout message invalide est ignoré et jamais relayé. */
    private fun open(id: String, p: String): Opened? = try {
        val r = Base64.decode(p, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, r, 0, 12)); c.updateAAD(id.toByteArray())
        val pl = c.doFinal(r, 12, r.size - 12)
        var o = 0
        val nl = pl[o++].toInt() and 0xff; val nb = pl.copyOfRange(o, o + nl); o += nl
        val pk = pl.copyOfRange(o, o + 65); o += 65
        val ts = ByteBuffer.wrap(pl, o, 8).long; o += 8
        val sl = pl[o++].toInt() and 0xff; val sig = pl.copyOfRange(o, o + sl); o += sl
        val tb = pl.copyOfRange(o, pl.size)
        if (tb.isEmpty() || !Ident.verify(pk, sd(id, ts, nb, tb), sig)) null
        else Opened(String(nb).replace(Regex("[#\\p{Cntrl}]"), "").take(12), tb, ts, Ident.tag(pk), pk)
    } catch (e: Exception) { null }

    private fun remember(id: String) {
        seen.add(id)
        while (seen.size > MAX_SEEN_IDS) seen.remove(seen.first())
    }

    private fun allow(from: String): Boolean {
        if (from !in rate && rate.size >= MAX_TRACKED_SENDERS) rate.remove(rate.keys.first())
        val now = System.currentTimeMillis(); val q = rate.getOrPut(from) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
        if (q.size >= 25) return false
        q.addLast(now); return true
    }

    private fun handle(m: Opened, hops: Int) {
        val type = m.body[0].toInt(); val d = m.body.copyOfRange(1, m.body.size)
        when (type) {
            0 -> onMsg(m.name + "#" + m.tag, clean(String(d)), hops)
            1 -> if (d.size == 32 && m.tag != tag) {
                val isNew = people[m.tag] == null
                people[m.tag] = Person(m.tag, m.name, d, System.currentTimeMillis())
                if (isNew) { onPeople(people.values.toList()); h.postDelayed({ announce() }, 400) }
            }
            2 -> if (d.size >= 4 && d.copyOfRange(0, 3).contentEquals(tagBytes)) priv(m.tag, m.name + "#" + m.tag, d[3].toInt(), d.copyOfRange(4, d.size), hops)
        }
    }

    private fun keep(b: ByteArray) {
        val now = System.currentTimeMillis()
        store.addLast(now to b)
        while (store.size > 60 || (store.isNotEmpty() && now - store.first().first > 3_600_000)) store.removeFirst()
    }

    private fun relay(b: ByteArray, except: String?, origin: String? = null, awareExcept: String? = null) {
        peers.forEach { (address, peer) ->
            if (origin != "ble" || address != except) enqueue(peer, b)
        }
        wifiAware?.send(b, if (origin == "aware") awareExcept else null)
        subscribers.keys.toList().forEach { address ->
            if (origin != "ble" || address != except) enqueueServer(address, b)
        }
        if (origin != "nostr") nostr?.send(b)
    }

    private fun enqueue(peer: Peer, frame: ByteArray) {
        val maxWrite = (peer.mtu - 3).coerceAtLeast(20)
        val pieces = if (frame.size <= maxWrite) listOf(frame) else fragmentBle(frame, maxWrite)
        if (pieces.isEmpty() || peer.q.size + pieces.size > MAX_QUEUED_FRAMES_PER_PEER) return
        pieces.forEach(peer.q::addLast)
        pump(peer)
    }

    private fun enqueueServer(address: String, frame: ByteArray) {
        if (address !in subscribers) return
        val maxWrite = ((serverMtu[address] ?: 23) - 3).coerceAtLeast(20)
        val pieces = if (frame.size <= maxWrite) listOf(frame) else fragmentBle(frame, maxWrite)
        if (pieces.isEmpty() || serverQueue.size + pieces.size > MAX_SERVER_QUEUE) return
        pieces.forEach { serverQueue.addLast(ServerNotification(address, it)) }
        pumpServer()
    }

    @Suppress("DEPRECATION")
    private fun pumpServer() {
        if (serverInFlight != null) return
        val srv = server ?: return
        val characteristic = serverCharacteristic ?: return
        while (serverQueue.isNotEmpty()) {
            val item = serverQueue.removeFirst()
            val device = subscribers[item.address] ?: continue
            serverInFlight = item
            val sent = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    srv.notifyCharacteristicChanged(device, characteristic, false, item.bytes) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.value = item.bytes
                    srv.notifyCharacteristicChanged(device, characteristic, false)
                }
            } catch (_: Exception) { false }
            if (sent) {
                h.postDelayed({
                    if (serverInFlight === item) {
                        serverInFlight = null
                        subscribers.remove(item.address)
                        serverQueue.removeAll { it.address == item.address }
                        runCatching { srv.cancelConnection(device) }
                        onTransportStatus("Bluetooth PC : connexion interrompue")
                        pumpServer()
                    }
                }, 10_000)
                return
            }
            serverInFlight = null
            serverQueue.removeAll { it.address == item.address }
        }
    }

    private fun fragmentBle(frame: ByteArray, maxWrite: Int): List<ByteArray> =
        BleFramer.fragment(frame, maxWrite, fragmentSequence.getAndIncrement())

    private fun unwrapBle(from: String, packet: ByteArray): ByteArray? {
        if (packet.size < 2 || packet[0] != BLE_MAGIC_0 || packet[1] != BLE_MAGIC_1) return packet
        if (packet.size !in (BLE_FRAGMENT_HEADER + 1)..520) return null
        val b = ByteBuffer.wrap(packet); b.position(2)
        val id = b.int
        val part = b.get().toInt() and 0xff
        val count = b.get().toInt() and 0xff
        if (count !in 1..MAX_FRAGMENTS_PER_FRAME || part !in 0 until count) return null
        val now = System.currentTimeMillis()
        bleFragments.values.forEach { parts -> parts.entries.removeAll { now - it.value.created > 15_000 } }
        bleFragments.entries.removeAll { it.value.isEmpty() }
        if (from !in bleFragments && bleFragments.size >= MAX_TRACKED_SENDERS) bleFragments.remove(bleFragments.keys.first())
        val source = bleFragments.getOrPut(from) { LinkedHashMap() }
        if (source.size >= 4 && id !in source) source.remove(source.keys.first())
        val assembly = source.getOrPut(id) { BleAssembly(count, arrayOfNulls(count), now) }
        if (assembly.count != count || assembly.parts[part] != null) return null
        val fragment = ByteArray(b.remaining()); b.get(fragment)
        if (assembly.bytes + fragment.size > 520) { source.remove(id); return null }
        assembly.parts[part] = fragment; assembly.bytes += fragment.size
        if (assembly.parts.any { it == null }) return null
        val complete = ByteArrayOutputStream().apply { assembly.parts.forEach { write(it!!) } }.toByteArray()
        source.remove(id)
        return complete.takeIf { it.size in 1..520 }
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

    private fun receive(packet: ByteArray, from: String, origin: String = "ble") {
        val b = if (origin == "ble") unwrapBle(from, packet) ?: return else packet
        if (b.size > 520 || !allow(from)) return
        val j = try { JSONObject(String(b)) } catch (e: Exception) { return }
        val id = j.optString("i"); val ttl = j.optInt("t")
        if (id.length != 16 || id in seen || ttl !in 1..TTL0) return
        val m = open(id, j.optString("p")) ?: return
        if (abs(System.currentTimeMillis() - m.ts) > 7_200_000) return
        if ((m.tag == tag && !MessageDigest.isEqual(Ident.pub(), m.signingKey)) ||
            !identityPins.checkAndRemember(m.tag, m.signingKey)) {
            onTransportStatus("Identité ambiguë : message ignoré")
            return
        }
        remember(id)
        handle(m, TTL0 - ttl)
        if (ttl > 1) {
            j.put("t", ttl - 1)
            val out = j.toString().toByteArray()
            if (m.body[0].toInt() == 0) keep(out)
            relay(out, if (origin == "ble") from else null, origin, if (origin == "aware") from else null)
        }
    }

    private val srvCb = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(d: BluetoothDevice, req: Int, c: BluetoothGattCharacteristic,
                                                  prep: Boolean, resp: Boolean, off: Int, v: ByteArray) {
            val valid = c.uuid == CHR && !prep && off == 0 && v.size in 1..520
            if (resp) server?.sendResponse(d, req,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (valid) h.post { receive(v.copyOf(), d.address, "ble") }
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, req: Int, off: Int, descriptor: BluetoothGattDescriptor) {
            h.post {
                val valid = descriptor.uuid == CCCD && off == 0
                val value = if (d.address in subscribers) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                server?.sendResponse(d, req, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0, if (valid) value else null)
            }
        }

        override fun onDescriptorWriteRequest(d: BluetoothDevice, req: Int, descriptor: BluetoothGattDescriptor,
                                              prep: Boolean, resp: Boolean, off: Int, value: ByteArray) {
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val valid = descriptor.uuid == CCCD && !prep && off == 0 && (enable || disable)
            h.post {
                val admitted = valid && (!enable || d.address in subscribers || subscribers.size < MAX_SERVER_SUBSCRIBERS)
                if (admitted) {
                    if (enable) subscribers[d.address] = d else {
                        subscribers.remove(d.address)
                        serverQueue.removeAll { it.address == d.address }
                    }
                }
                if (resp) server?.sendResponse(d, req,
                    if (admitted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
                if (admitted && enable) {
                    h.postDelayed({
                        if (d.address in subscribers) {
                            store.forEach { enqueueServer(d.address, it.second) }
                            announce()
                        }
                    }, 500)
                }
            }
        }

        override fun onMtuChanged(d: BluetoothDevice, mtu: Int) { h.post { serverMtu[d.address] = mtu } }

        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) h.post {
                subscribers.remove(d.address); serverMtu.remove(d.address); bleFragments.remove(d.address)
                serverQueue.removeAll { it.address == d.address }
                if (serverInFlight?.address == d.address) serverInFlight = null
                pumpServer()
            }
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) {
            h.post {
                if (serverInFlight?.address != d.address) return@post
                serverInFlight = null
                if (status != BluetoothGatt.GATT_SUCCESS) serverQueue.removeAll { it.address == d.address }
                pumpServer()
            }
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

    companion object {
        private const val BLE_FRAGMENT_HEADER = 8
        private const val MAX_SERVER_SUBSCRIBERS = 8
        private const val MAX_SERVER_QUEUE = 256
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BLE_MAGIC_0 = 0x4d.toByte()
        private val BLE_MAGIC_1 = 0x46.toByte()
    }

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
                store.forEach { enqueue(p, it.second) }; onPeers(peers.size); h.postDelayed({ announce() }, 1500)
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            h.post { peers[g.device.address]?.let { it.busy = false; pump(it) } }
        }
    }
}
