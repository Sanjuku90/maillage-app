package app.maillage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

/** Identité de l'appareil : clé ECDSA P-256 dans l'Android Keystore (la clé privée ne peut pas être extraite). */
object Ident {
    private const val A = "maillage_id"
    private val HDR = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun init() {
        if (keystore().containsAlias(A)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(A, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }

    fun pub(): ByteArray { init(); return keystore().getCertificate(A).publicKey.encoded.takeLast(65).toByteArray() }

    fun sign(d: ByteArray): ByteArray {
        init()
        return Signature.getInstance("SHA256withECDSA").run { initSign(keystore().getKey(A, null) as PrivateKey); update(d); sign() }
    }

    fun verify(pk: ByteArray, d: ByteArray, sig: ByteArray): Boolean = try {
        val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(HDR + pk))
        Signature.getInstance("SHA256withECDSA").run { initVerify(k); update(d); verify(sig) }
    } catch (e: Exception) { false }

    fun tag(pk: ByteArray) = MessageDigest.getInstance("SHA-256").digest(pk).take(3).joinToString("") { "%02x".format(it) }
    fun ts(t: Long): ByteArray = ByteBuffer.allocate(8).putLong(t).array()
    fun reset() { keystore().deleteEntry(A) }
}

/** Chiffre ce qui est stocké sur le téléphone (mot de passe, historique) avec une clé AES du Keystore. */
object Vault {
    private const val A = "maillage_vault"
    private fun key(): SecretKey {
        val ks = keystore()
        if (!ks.containsAlias(A)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(A, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return ks.getKey(A, null) as SecretKey
    }
    fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    fun reset() { keystore().deleteEntry(A) }
    fun dec(s: String): String {
        val r = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, r, 0, 12))
        return String(c.doFinal(r, 12, r.size - 12))
    }
}
