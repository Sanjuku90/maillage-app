package app.maillage

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Optional local Wi-Fi Aware bearer. Uses bounded fragments because discovery messages are small. */
@SuppressLint("MissingPermission")
class WifiAwareTransport(
    private val context: Context,
    private val localId: ByteArray,
    private val channelTag: String,
    private val onFrame: (String, ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private var awareSession: WifiAwareSession? = null
    private var publisher: PublishDiscoverySession? = null
    private var subscriber: SubscribeDiscoverySession? = null
    private val peers = LinkedHashMap<String, PeerHandle>()
    private val assemblies = LinkedHashMap<String, Assembly>()
    private val pending = LinkedHashMap<Int, PendingSend>()
    private val msgId = AtomicInteger(1)
    private val transferId = AtomicInteger(1)
    private var running = false

    private data class Assembly(val count: Int, val parts: Array<ByteArray?>, val created: Long, var bytes: Int = 0)
    private data class PendingSend(val session: DiscoverySession, val peer: PeerHandle, val bytes: ByteArray, var tries: Int = 0)

    fun start() {
        if (running) return
        val m = manager
        if (m == null || !context.packageManager.hasSystemFeature("android.hardware.wifi.aware")) {
            onStatus("Wi-Fi Aware non pris en charge sur cet appareil")
            return
        }
        if (!m.isAvailable) {
            onStatus("Wi-Fi Aware indisponible, le BLE reste actif")
            return
        }
        running = true
        m.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                if (!running) { session.close(); return }
                awareSession = session
                val serviceName = "$SERVICE_PREFIX$channelTag"
                session.publish(PublishConfig.Builder().setServiceName(serviceName).setServiceSpecificInfo(localId).build(), publishCallback, handler)
                session.subscribe(SubscribeConfig.Builder().setServiceName(serviceName).build(), subscribeCallback, handler)
                onStatus("Wi-Fi Aware actif")
            }
            override fun onAttachFailed() { running = false; onStatus("Wi-Fi Aware n’a pas pu démarrer, le BLE reste actif") }
        }, handler)
    }

    fun stop() {
        running = false
        publisher?.close(); publisher = null
        subscriber?.close(); subscriber = null
        awareSession?.close(); awareSession = null
        peers.clear(); assemblies.clear(); pending.clear()
        handler.removeCallbacksAndMessages(null)
    }

    fun send(frame: ByteArray, exceptPeerId: String? = null) {
        if (!running || frame.isEmpty() || frame.size > 520) return
        val session = subscriber ?: return
        val id = transferId.getAndIncrement()
        val count = (frame.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        if (count !in 1..8) return
        val targets = peers.entries.filter { it.key != exceptPeerId }.toList()
        for ((peerId, peer) in targets) {
            for (part in 0 until count) {
                val start = part * CHUNK_BYTES
                val end = minOf(frame.size, start + CHUNK_BYTES)
                val packet = ByteBuffer.allocate(HEADER_BYTES + end - start)
                    .put(MAGIC_0).put(MAGIC_1).put(localId).putInt(id).put(part.toByte()).put(count.toByte())
                    .put(frame, start, end - start).array()
                sendFragment(session, peer, packet, 0)
            }
        }
    }

    private fun sendFragment(session: DiscoverySession, peer: PeerHandle, bytes: ByteArray, retries: Int) {
        val id = msgId.getAndIncrement()
        pending[id] = PendingSend(session, peer, bytes, retries)
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
        try {
            session.sendMessage(peer, id, bytes)
            handler.postDelayed({ pending.remove(id) }, 15_000)
        } catch (_: Exception) {
            pending.remove(id)
        }
    }

    private val publishCallback = object : android.net.wifi.aware.DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) { publisher = session }
        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { acceptFragment(message) }
        override fun onMessageSendSucceeded(messageId: Int) { pending.remove(messageId) }
        override fun onMessageSendFailed(messageId: Int) { retry(messageId) }
    }

    private val subscribeCallback = object : android.net.wifi.aware.DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: SubscribeDiscoverySession) { subscriber = session }
        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
            if (!running || serviceSpecificInfo.size != ID_BYTES || serviceSpecificInfo.contentEquals(localId)) return
            val remoteId = serviceSpecificInfo.joinToString("") { "%02x".format(it) }
            peers[remoteId] = peerHandle
            onStatus("Wi-Fi Aware : ${peers.size} voisin(s)")
        }
        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            val remoteId = peers.entries.firstOrNull { it.value == peerHandle }?.key ?: return
            peers.remove(remoteId)
            onStatus("Wi-Fi Aware : ${peers.size} voisin(s)")
        }
        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { acceptFragment(message) }
        override fun onMessageSendSucceeded(messageId: Int) { pending.remove(messageId) }
        override fun onMessageSendFailed(messageId: Int) { retry(messageId) }
    }

    private fun retry(id: Int) {
        val p = pending.remove(id) ?: return
        if (p.tries >= 2 || !running) return
        handler.postDelayed({ sendFragment(p.session, p.peer, p.bytes, p.tries + 1) }, 250L * (p.tries + 1))
    }

    private fun acceptFragment(bytes: ByteArray) {
        if (bytes.size !in (HEADER_BYTES + 1)..255 || bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return
        val b = ByteBuffer.wrap(bytes)
        b.position(2)
        val sender = ByteArray(ID_BYTES); b.get(sender)
        if (sender.contentEquals(localId)) return
        val key = sender.joinToString("") { "%02x".format(it) }
        val id = b.int
        val part = b.get().toInt() and 0xff
        val count = b.get().toInt() and 0xff
        if (count !in 1..8 || part !in 0 until count) return
        val k = "$key:$id"
        val now = System.currentTimeMillis()
        assemblies.entries.removeAll { now - it.value.created > 15_000 }
        if (assemblies.size >= MAX_ASSEMBLIES && k !in assemblies) assemblies.remove(assemblies.keys.first())
        val a = assemblies.getOrPut(k) { Assembly(count, arrayOfNulls(count), now) }
        if (a.count != count || a.parts[part] != null) return
        val data = ByteArray(b.remaining()); b.get(data)
        if (data.isEmpty() || a.bytes + data.size > 520) { assemblies.remove(k); return }
        a.parts[part] = data; a.bytes += data.size
        if (a.parts.all { it != null }) {
            val frame = ByteArrayOutputStream().apply { a.parts.forEach { write(it!!) } }.toByteArray()
            assemblies.remove(k)
            if (frame.size in 1..520) onFrame(key, frame)
        }
    }

    companion object {
        private const val SERVICE_PREFIX = "maillage-"
        private const val ID_BYTES = 8
        private const val HEADER_BYTES = 16
        private const val CHUNK_BYTES = 175
        private const val MAX_ASSEMBLIES = 64
        private const val MAX_PENDING = 256
        private val MAGIC_0 = 0x4d.toByte()
        private val MAGIC_1 = 0x46.toByte()
    }
}
