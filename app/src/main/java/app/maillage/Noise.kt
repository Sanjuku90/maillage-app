package app.maillage

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Même famille de crypto que Bitchat : Argon2id (mots de passe de canal), Noise XX (X25519 + ChaCha20-Poly1305 + SHA-256). */
object Kdf {
    fun argon2id(pw: String): ByteArray {
        val p = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt("maillage-salt-v3".toByteArray()).withIterations(2).withMemoryAsKB(19456).withParallelism(1).build()
        val g = Argon2BytesGenerator(); g.init(p)
        return ByteArray(32).also { g.generateBytes(pw.toByteArray(), it) }
    }
}

object Dh {
    private val rnd = SecureRandom()
    fun gen(): Pair<ByteArray, ByteArray> {
        val k = ByteArray(32); X25519.generatePrivateKey(rnd, k)
        val p = ByteArray(32); X25519.generatePublicKey(k, 0, p, 0)
        return k to p
    }
    fun pub(priv: ByteArray): ByteArray = ByteArray(32).also { X25519.generatePublicKey(priv, 0, it, 0) }
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val r = ByteArray(32)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, r, 0)) throw SecurityException("clé publique invalide")
        return r
    }
}

private fun sha(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d)
private fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
private fun hkdf(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
    val t = hmac(ck, ikm); val o1 = hmac(t, byteArrayOf(1)); val o2 = hmac(t, o1 + byteArrayOf(2)); return o1 to o2
}
private fun aead(enc: Boolean, k: ByteArray, n: Long, ad: ByteArray, inp: ByteArray): ByteArray {
    val nonce = ByteArray(12); for (i in 0..7) nonce[4 + i] = (n shr (8 * i)).toByte()
    val c = ChaCha20Poly1305(); c.init(enc, AEADParameters(KeyParameter(k), 128, nonce, ad))
    val out = ByteArray(c.getOutputSize(inp.size)); var l = c.processBytes(inp, 0, inp.size, out, 0); l += c.doFinal(out, l)
    return out.copyOf(l)
}

/** Noise_XX_25519_ChaChaPoly_SHA256 : -> e | <- e, ee, s, es | -> s, se. */
class Handshake private constructor(
    private val init: Boolean,
    private val sPriv: ByteArray,
    private val sPub: ByteArray,
    private val ephemeralGenerator: () -> Pair<ByteArray, ByteArray>,
    prologue: ByteArray
) {
    /** Production sessions always use a fresh random ephemeral and the existing empty prologue. */
    constructor(init: Boolean, sPriv: ByteArray, sPub: ByteArray) :
        this(init, sPriv, sPub, { Dh.gen() }, ByteArray(0))

    /** Fixed ephemeral and prologue for reproducible public test vectors only. */
    internal constructor(init: Boolean, sPriv: ByteArray, sPub: ByteArray, testEphemeral: ByteArray, testPrologue: ByteArray) :
        this(init, sPriv, sPub, fixedGenerator(testEphemeral), testPrologue.copyOf())

    private companion object {
        fun fixedGenerator(privateKey: ByteArray): () -> Pair<ByteArray, ByteArray> {
            require(privateKey.size == 32) { "clé éphémère invalide" }
            val fixed = privateKey.copyOf()
            return { fixed.copyOf() to Dh.pub(fixed) }
        }
    }

    private var h: ByteArray
    private var ck: ByteArray
    private var k: ByteArray? = null
    private var n = 0L
    private var step = 0
    private var ePriv = ByteArray(0); private var ePub = ByteArray(0); private var re = ByteArray(0)
    var rs = ByteArray(0); private set

    init {
        val nb = "Noise_XX_25519_ChaChaPoly_SHA256".toByteArray()
        h = if (nb.size <= 32) nb.copyOf(32) else sha(nb); ck = h.copyOf()
        mixHash(prologue)
    }

    private fun mixHash(d: ByteArray) { h = sha(h + d) }
    private fun mixKey(ikm: ByteArray) { val (a, b) = hkdf(ck, ikm); ck = a; k = b; n = 0 }
    private fun encH(pt: ByteArray): ByteArray { val kk = k; val c = if (kk == null) pt else aead(true, kk, n++, h, pt); mixHash(c); return c }
    private fun decH(ct: ByteArray): ByteArray { val kk = k; val p = if (kk == null) ct else aead(false, kk, n++, h, ct); mixHash(ct); return p }
    private fun genE() { val (a, b) = ephemeralGenerator(); ePriv = a; ePub = b }

    fun write(payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        when {
            init && step == 0 -> { genE(); o.write(ePub); mixHash(ePub) }
            !init && step == 1 -> { genE(); o.write(ePub); mixHash(ePub); mixKey(Dh.dh(ePriv, re)); o.write(encH(sPub)); mixKey(Dh.dh(sPriv, re)) }
            init && step == 2 -> { o.write(encH(sPub)); mixKey(Dh.dh(sPriv, re)) }
            else -> throw IllegalStateException("ordre du handshake")
        }
        o.write(encH(payload)); step++; return o.toByteArray()
    }

    fun read(m: ByteArray): ByteArray {
        var o = 0
        when {
            !init && step == 0 -> { re = m.copyOfRange(0, 32); o = 32; mixHash(re) }
            init && step == 1 -> {
                re = m.copyOfRange(0, 32); o = 32; mixHash(re); mixKey(Dh.dh(ePriv, re))
                rs = decH(m.copyOfRange(o, o + 48)); o += 48; mixKey(Dh.dh(ePriv, rs))
            }
            !init && step == 2 -> { rs = decH(m.copyOfRange(0, 48)); o = 48; mixKey(Dh.dh(ePriv, rs)) }
            else -> throw IllegalStateException("ordre du handshake")
        }
        val p = decH(m.copyOfRange(o, m.size)); step++; return p
    }

    fun split(): Transport {
        check(step == 3) { "handshake incomplet" }
        val (a, b) = hkdf(ck, ByteArray(0)); return if (init) Transport(a, b) else Transport(b, a)
    }
}

/** Messages privés après le handshake : nonce explicite + fenêtre anti-rejeu. */
class Transport(private val sk: ByteArray, private val rk: ByteArray) {
    private var sn = 0L; private var max = -1L; private val seen = HashSet<Long>()
    fun enc(pt: ByteArray): ByteArray {
        check(sn >= 0 && sn < Long.MAX_VALUE) { "session expirée : compteur épuisé" }
        val n = sn++
        return Ident.ts(n) + aead(true, sk, n, ByteArray(0), pt)
    }
    fun dec(pkt: ByteArray): ByteArray {
        if (pkt.size < 24) throw SecurityException("paquet trop court")
        val n = ByteBuffer.wrap(pkt, 0, 8).long
        if (n < 0 || n < max - 64 || n in seen) throw SecurityException("rejeu")
        val p = aead(false, rk, n, ByteArray(0), pkt.copyOfRange(8, pkt.size))
        seen.add(n); if (n > max) max = n; if (seen.size > 256) seen.removeIf { it < max - 64 }
        return p
    }
}

object SelfTest {
    fun run(): String = try {
        val a = Kdf.argon2id("motdepasse-test"); val b = Kdf.argon2id("motdepasse-test"); val c = Kdf.argon2id("autre-mot-de-passe")
        check(a.contentEquals(b) && !a.contentEquals(c)) { "Argon2id incohérent" }
        val (ip, iu) = Dh.gen(); val (rp, ru) = Dh.gen()
        val i = Handshake(true, ip, iu); val r = Handshake(false, rp, ru)
        check(String(r.read(i.write("un".toByteArray()))) == "un") { "Noise message 1" }
        check(String(i.read(r.write("deux".toByteArray()))) == "deux") { "Noise message 2" }
        check(String(r.read(i.write("trois".toByteArray()))) == "trois") { "Noise message 3" }
        check(i.rs.contentEquals(ru) && r.rs.contentEquals(iu)) { "Identités mutuelles" }
        val ti = i.split(); val tr = r.split()
        val e1 = ti.enc("salut".toByteArray())
        check(String(tr.dec(e1)) == "salut") { "Message privé aller" }
        check(runCatching { tr.dec(e1) }.isFailure) { "Rejeu non bloqué" }
        val bad = ti.enc("x".toByteArray()); bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        check(runCatching { tr.dec(bad) }.isFailure) { "Message modifié accepté" }
        check(String(ti.dec(tr.enc("retour".toByteArray()))) == "retour") { "Message privé retour" }
        "✅ Tests locaux réussis : Argon2id, Noise XX, anti-rejeu et détection de modification. Ce résultat n’est ni un audit ni une preuve de compatibilité."
    } catch (e: Throwable) { "❌ Échec : ${e.message}" }
}
