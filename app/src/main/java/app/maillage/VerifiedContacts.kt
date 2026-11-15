package app.maillage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores only user-confirmed public fingerprints, encrypted with Android Keystore.
 * Corrupt or inaccessible storage fails closed; it is never silently replaced.
 * All operations are performed on the mesh's main thread.
 */
internal class VerifiedContacts(context: Context, private val onFailure: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("maillage", Context.MODE_PRIVATE)
    private var available = true
    private var entries = linkedMapOf<String, String>()
    private var changed = linkedSetOf<String>()

    init {
        try {
            val encrypted = prefs.getString(KEY, null)
            if (encrypted != null) {
                require(encrypted.length <= MAX_CIPHERTEXT_CHARS)
                val root = JSONObject(Vault.dec(encrypted))
                require(root.getInt("version") == 1)
                val obj = root.getJSONObject("contacts")
                require(obj.length() <= MAX_CONTACTS)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val tag = keys.next()
                    val fingerprint = obj.getString(tag)
                    require(validTag(tag) && validFingerprint(fingerprint))
                    entries[tag] = fingerprint
                }
                val changedArray = root.getJSONArray("changed")
                require(changedArray.length() <= MAX_CONTACTS)
                for (i in 0 until changedArray.length()) {
                    val tag = changedArray.getString(i)
                    require(tag in entries && changed.add(tag))
                }
            }
        } catch (_: Exception) {
            entries.clear()
            changed.clear()
            available = false
            onFailure("Vérifications de contacts indisponibles : messages privés bloqués")
        }
    }

    fun state(tag: String, observed: String?): ContactVerification {
        if (!validTag(tag)) return ContactVerification.UNAVAILABLE
        val checked = observed?.takeIf(::validFingerprint)
        val saved = entries[tag]
        if (available && saved != null && checked != null && saved != checked && tag !in changed) {
            val updated = LinkedHashSet(changed)
            updated.add(tag)
            if (!save(LinkedHashMap(entries), updated)) {
                available = false
                onFailure("Changement d’identité non enregistré : messages privés bloqués")
                return ContactVerification.UNAVAILABLE
            }
        }
        return ContactVerificationPolicy.evaluate(available, saved, checked, tag in changed)
    }

    fun savedFingerprint(tag: String): String? = if (available && validTag(tag)) entries[tag] else null

    /** Caller must confirm the fingerprint has not changed since it was shown. */
    fun verify(tag: String, fingerprint: String): Boolean {
        if (!available || !validTag(tag) || !validFingerprint(fingerprint) || tag in entries) return false
        if (entries.size >= MAX_CONTACTS) {
            onFailure("Limite des contacts vérifiés atteinte")
            return false
        }
        val replacement = LinkedHashMap(entries)
        replacement[tag] = fingerprint
        return save(replacement, LinkedHashSet(changed))
    }

    fun forget(tag: String): Boolean {
        if (!available || !validTag(tag) || tag !in entries) return false
        val replacement = LinkedHashMap(entries)
        replacement.remove(tag)
        val updated = LinkedHashSet(changed)
        updated.remove(tag)
        return save(replacement, updated)
    }

    private fun save(replacement: LinkedHashMap<String, String>, updated: LinkedHashSet<String>): Boolean = try {
        val json = JSONObject()
        replacement.forEach { (tag, fingerprint) -> json.put(tag, fingerprint) }
        val flags = JSONArray()
        updated.forEach { flags.put(it) }
        val root = JSONObject().put("version", 1).put("contacts", json).put("changed", flags)
        val encrypted = Vault.enc(root.toString())
        require(encrypted.length <= MAX_CIPHERTEXT_CHARS)
        check(prefs.edit().putString(KEY, encrypted).commit())
        entries = replacement
        changed = updated
        true
    } catch (_: Exception) {
        onFailure("Vérification non enregistrée : réessaie avant de faire confiance")
        false
    }

    private fun validTag(tag: String) = tag.matches(Regex("[0-9a-f]{6}"))
    private fun validFingerprint(s: String) = s.matches(Regex("[0-9a-f]{4}( [0-9a-f]{4}){15}"))

    private companion object {
        const val KEY = "verified_contacts_v1"
        const val MAX_CONTACTS = 100
        const val MAX_CIPHERTEXT_CHARS = 40_000
    }
}
