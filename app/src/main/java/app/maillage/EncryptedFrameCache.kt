package app.maillage

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retains already-encrypted group frames locally for best-effort BLE replay.
 * The serialized cache is additionally encrypted with Android Keystore via Vault.
 * This is not an outbox with acknowledgements: no recipient delivery is guaranteed.
 * All calls must be on the mesh's main-thread handler.
 */
internal class EncryptedFrameCache(
    context: Context,
    private val channelTag: String,
    private val onFailure: (String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val prefs = context.getSharedPreferences("maillage", Context.MODE_PRIVATE)
    private val frames = RecentFrames(MAX_FRAMES, MAX_AGE_MS)
    private var lastPersistedSize = 0

    init { restore() }

    private fun restore() {
        try {
            val cipher = prefs.getString(CACHE_KEY, null) ?: return
            require(cipher.length <= MAX_STORED_CHARS) { "cache trop volumineux" }
            val root = JSONObject(Vault.dec(cipher))
            require(root.optInt("version", 0) == 1) { "version de cache non prise en charge" }
            if (root.optString("channel") != channelTag) {
                if (!prefs.edit().remove(CACHE_KEY).commit()) throw IllegalStateException("suppression impossible")
                return
            }
            val items = root.getJSONArray("frames")
            require(items.length() <= MAX_FRAMES) { "trop de trames" }
            val current = now()
            val valid = ArrayList<Pair<Long, ByteArray>>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val created = item.getLong("time")
                val encoded = item.getString("frame")
                require(encoded.length <= MAX_ENCODED_FRAME_CHARS) { "trame trop volumineuse" }
                val frame = Base64.decode(encoded, Base64.NO_WRAP)
                require(frame.size in 1..MAX_FRAME_BYTES) { "taille de trame invalide" }
                if (created >= 0 && created <= current && current - created < MAX_AGE_MS) valid.add(created to frame)
            }
            valid.sortBy { it.first }
            valid.forEach { (created, frame) -> frames.add(frame, created) }
            lastPersistedSize = items.length()
            if (valid.size != items.length()) persist(current)
        } catch (_: Exception) {
            frames.clear()
            onFailure("Cache local illisible : les trames précédentes ne seront pas reprises")
        }
    }

    fun add(frame: ByteArray) {
        if (frame.size !in 1..MAX_FRAME_BYTES) {
            onFailure("Cache local : trame invalide, livraison non confirmée")
            return
        }
        val current = now()
        val existing = frames.snapshotRecords(current)
        if (existing.size >= MAX_FRAMES) onFailure("Cache local plein : la trame la plus ancienne sera retirée")
        frames.add(frame, current)
        persist(current)
    }

    fun snapshot(): List<ByteArray> {
        val current = now()
        val records = frames.snapshotRecords(current)
        if (records.size != lastPersistedSize) persist(current)
        return records.map { it.second }
    }

    /** Forget RAM only. Persisted entries are kept for the next launch. */
    fun close() = frames.clear()

    private fun persist(current: Long) {
        try {
            val records = frames.snapshotRecords(current)
            val array = JSONArray()
            records.forEach { (created, frame) ->
                array.put(JSONObject().put("time", created).put("frame", Base64.encodeToString(frame, Base64.NO_WRAP)))
            }
            if (records.isEmpty()) {
                if (!prefs.edit().remove(CACHE_KEY).commit()) throw IllegalStateException("suppression impossible")
            } else {
                val json = JSONObject().put("version", 1).put("channel", channelTag).put("frames", array).toString()
                val cipher = Vault.enc(json)
                require(cipher.length <= MAX_STORED_CHARS) { "cache trop volumineux" }
                if (!prefs.edit().putString(CACHE_KEY, cipher).commit()) throw IllegalStateException("sauvegarde impossible")
            }
            lastPersistedSize = records.size
        } catch (_: Exception) {
            onFailure("Cache local non enregistré : reprise après redémarrage non garantie")
        }
    }

    private companion object {
        const val CACHE_KEY = "mesh_frame_cache_v1"
        const val MAX_FRAMES = 60
        const val MAX_AGE_MS = 3_600_000L
        const val MAX_FRAME_BYTES = 520
        const val MAX_ENCODED_FRAME_CHARS = 704
        const val MAX_STORED_CHARS = 100_000
    }
}
