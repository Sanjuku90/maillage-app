package app.maillage

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** NIP-01 text-note relay carrying only already-encrypted Maillage frames. */
class NostrRelayTransport(
    private val secretHex: String,
    private val channelTag: String,
    torSocksPort: Int?,
    private val onFrame: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val relays = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.nostr.band")
    private val io = Executors.newSingleThreadExecutor()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val retrying = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val eventSeen = LinkedHashSet<String>()
    private val relayEvents = HashMap<String, ArrayDeque<Long>>()
    private val closed = AtomicBoolean(false)
    private val secp = Secp256k1.get()
    private val secret = hexToBytes(secretHex)
    private val pub = secp.pubkeyCreate(secret).copyOfRange(1, 33)
    private val pubHex = bytesToHex(pub)
    private val subId = "maillage-$channelTag"
    private val client = OkHttpClient.Builder().apply {
        pingInterval(30, TimeUnit.SECONDS)
        connectTimeout(20, TimeUnit.SECONDS)
        readTimeout(0, TimeUnit.MILLISECONDS)
        if (torSocksPort != null) {
            proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", torSocksPort)))
            // If the stack attempts a local DNS lookup instead of proxy-side resolution, fail closed.
            dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    throw java.net.UnknownHostException("DNS local désactivé en mode Tor")
                }
            })
        }
    }.build()

    init {
        require(secret.size == 32 && secp.secKeyVerify(secret)) { "Clé Nostr invalide" }
        relays.forEach { pending[it] = ArrayDeque() }
    }

    fun start() {
        if (closed.get()) return
        relays.forEach(::connect)
    }

    fun send(frame: ByteArray) {
        if (closed.get() || frame.isEmpty() || frame.size > 520) return
        io.execute {
            if (closed.get()) return@execute
            try {
                val body = Base64.encodeToString(frame, Base64.NO_WRAP)
                val event = makeEvent(body)
                val wire = JSONObject().put("id", event.id).put("pubkey", event.pubkey)
                    .put("created_at", event.createdAt).put("kind", 1)
                    .put("tags", event.tags).put("content", event.content).put("sig", event.sig).toString()
                relays.forEach { relay ->
                    val socket = sockets[relay]
                    if (socket != null && socket.send(JSONArray().put("EVENT").put(JSONObject(wire)).toString())) return@forEach
                    synchronized(pending.getValue(relay)) {
                        val q = pending.getValue(relay)
                        if (q.size >= 100) q.removeFirst()
                        q.addLast(wire)
                    }
                }
            } catch (e: Exception) {
                onStatus("Relais Nostr: envoi impossible")
            }
        }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        sockets.values.forEach { it.close(1000, "closed") }
        sockets.clear()
        timer.shutdownNow()
        io.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        secret.fill(0)
    }

    private data class SignedEvent(
        val id: String, val pubkey: String, val createdAt: Long,
        val tags: JSONArray, val content: String, val sig: String
    )

    private fun makeEvent(content: String): SignedEvent {
        val now = System.currentTimeMillis() / 1000
        val tags = JSONArray().put(JSONArray().put("t").put("maillage_$channelTag"))
            .put(JSONArray().put("expiration").put((now + 7200).toString()))
        val canonical = JSONArray().put(0).put(pubHex).put(now).put(1).put(tags).put(content).toString().replace("\\/", "/")
        val idBytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val signature = secp.signSchnorr(idBytes, secret, null)
        return SignedEvent(bytesToHex(idBytes), pubHex, now, tags, content, bytesToHex(signature))
    }

    private fun connect(relay: String) {
        if (closed.get() || sockets.containsKey(relay) || !retrying.add(relay)) return
        val request = Request.Builder().url(relay).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retrying.remove(relay)
                sockets[relay] = webSocket
                webSocket.send(JSONArray().put("REQ").put(subId)
                    .put(JSONObject().put("kinds", JSONArray().put(1)).put("#t", JSONArray().put("maillage_$channelTag"))
                        .put("since", System.currentTimeMillis() / 1000 - 7200).put("limit", 200)).toString())
                val q = pending.getValue(relay)
                synchronized(q) {
                    while (q.isNotEmpty()) webSocket.send(JSONArray().put("EVENT").put(JSONObject(q.removeFirst())).toString())
                }
                onStatus("Relais Nostr connecté")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 64_000 || closed.get()) return
                try {
                    val msg = JSONArray(text)
                    if (msg.length() < 3 || msg.optString(0) != "EVENT" || msg.optString(1) != subId) return
                    if (!allowRelayEvent(relay)) return
                    acceptEvent(msg.getJSONObject(2))
                } catch (_: Exception) { }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(relay, webSocket)
                retryLater(relay)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(relay, webSocket)
                retrying.remove(relay)
                retryLater(relay)
            }
        })
    }

    private fun retryLater(relay: String) {
        if (!closed.get()) runCatching { timer.schedule({ connect(relay) }, 5, TimeUnit.SECONDS) }
    }

    @Synchronized
    private fun allowRelayEvent(relay: String): Boolean {
        val now = System.currentTimeMillis()
        val q = relayEvents.getOrPut(relay) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
        if (q.size >= 200) return false
        q.addLast(now)
        return true
    }

    private fun acceptEvent(e: JSONObject) {
        val id = e.optString("id"); val author = e.optString("pubkey"); val sig = e.optString("sig")
        val content = e.optString("content")
        val tags = e.optJSONArray("tags") ?: return
        if (id.length != 64 || author.length != 64 || sig.length != 128 || content.length > 4096 || tags.length() !in 1..32) return
        synchronized(eventSeen) { if (id in eventSeen) return }
        var channelFound = false
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.length() >= 2 && tag.optString(0) == "t" && tag.optString(1) == "maillage_$channelTag") channelFound = true
        }
        if (!channelFound || e.optInt("kind", -1) != 1) return
        val canonicalTags = JSONArray()
        for (i in 0 until tags.length()) {
            val src = tags.optJSONArray(i) ?: return
            if (src.length() == 0 || src.length() > 8) return
            val dst = JSONArray()
            for (j in 0 until src.length()) {
                if (src.isNull(j) || src.get(j) !is String) return
                dst.put(src.getString(j))
            }
            canonicalTags.put(dst)
        }
        val createdAt = e.optLong("created_at", 0)
        if (createdAt <= 0 || kotlin.math.abs(System.currentTimeMillis() / 1000 - createdAt) > 7200) return
        val canonical = JSONArray().put(0).put(author).put(createdAt).put(1).put(canonicalTags).put(content).toString().replace("\\/", "/")
        val idBytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        if (bytesToHex(idBytes) != id) return
        val signature = runCatching { hexToBytes(sig) }.getOrNull() ?: return
        val publicKey = runCatching { hexToBytes(author) }.getOrNull() ?: return
        if (signature.size != 64 || publicKey.size != 32 || !secp.verifySchnorr(signature, idBytes, publicKey)) return
        synchronized(eventSeen) {
            eventSeen.add(id)
            while (eventSeen.size > 2_000) eventSeen.remove(eventSeen.first())
        }
        val frame = runCatching { Base64.decode(content, Base64.DEFAULT) }.getOrNull() ?: return
        if (frame.isNotEmpty() && frame.size <= 520) onFrame(frame)
    }

    companion object {
        fun newSecretHex(): String {
            val secp = Secp256k1.get(); val random = SecureRandom()
            repeat(128) {
                val candidate = ByteArray(32).also(random::nextBytes)
                if (secp.secKeyVerify(candidate)) {
                    val hex = bytesToHex(candidate)
                    candidate.fill(0)
                    return hex
                }
                candidate.fill(0)
            }
            throw IllegalStateException("Impossible de créer une clé Nostr")
        }

        private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" })
            return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }

    private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" })
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
