package app.maillage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Noise-C public vector: Noise_XX_25519_ChaChaPoly_SHA256.
 * Fixture extracted from noise-c tests/vector/noise-c-basic.txt at commit
 * 5d0a74760320e5486ced302e36ccad91606aac43:
 * https://docs.rs/crate/minip2p-noise/latest/source/tests/fixtures/noise-c-basic-xx.txt
 * Public test keys below MUST NEVER be used as real identities.
 * Production continues to use an empty prologue and random ephemeral keys.
 */
class NoisePublicVectorTest {
    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun noiseCHandshakeAndTransportMatchPublicVector() {
        val prologue = bytes("50726f6c6f677565313233")
        val initStatic = bytes("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1")
        val initEphemeral = bytes("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
        val respStatic = bytes("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
        val respEphemeral = bytes("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
        val initiator = Handshake(true, initStatic, Dh.pub(initStatic), initEphemeral, prologue)
        val responder = Handshake(false, respStatic, Dh.pub(respStatic), respEphemeral, prologue)
        val payload1 = bytes("4c756477696720766f6e204d69736573")
        val payload2 = bytes("4d757272617920526f746862617264")
        val payload3 = bytes("462e20412e20486179656b")
        val m1 = initiator.write(payload1)
        assertEquals("ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573", hex(m1))
        assertArrayEquals(payload1, responder.read(m1))
        val m2 = responder.write(payload2)
        assertEquals("95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f14480884381cbad1f276e038c48378ffce2b65285e08d6b68aaa3629a5a8639392490e5b94a6d8798832d5372f220f161d9c2df035528f8982ffe09be9b5c412f8a0db5d21351f20af1370d0bf8ef1a8c59a30e", hex(m2))
        assertArrayEquals(payload2, initiator.read(m2))
        val m3 = initiator.write(payload3)
        assertEquals("c7195ffacac1307ff99046f219750fc47693e23c3cb08b89c2af808b444850a8589981dfbd651e6ff4724a781cc2aa6158c9fea0d4ec82a286427484c5b8c8123a7a6002b1de9f9775fc97", hex(m3))
        assertArrayEquals(payload3, responder.read(m3))
        assertArrayEquals(Dh.pub(respStatic), initiator.rs)
        assertArrayEquals(Dh.pub(initStatic), responder.rs)
        val ti = initiator.split(); val tr = responder.split()
        val payload4 = bytes("4361726c204d656e676572")
        val payload5 = bytes("4a65616e2d426170746973746520536179")
        val payload6 = bytes("457567656e2042f6686d20766f6e2042617765726b")
        // Maillage prepends an 8-byte counter; the public vector contains only the Noise ciphertext.
        val m4 = tr.enc(payload4)
        assertEquals("96763ed773f8e47bb3712f0e29b3060ffc956ffc146cee53d5e1df", hex(m4.copyOfRange(8, m4.size)))
        assertArrayEquals(payload4, ti.dec(m4))
        val m5 = ti.enc(payload5)
        assertEquals("3e40f15f6f3a46ae446b253bf8b1d9ffb6ed9b174d272328ff91a7e2e5c79c07f5", hex(m5.copyOfRange(8, m5.size)))
        assertArrayEquals(payload5, tr.dec(m5))
        val m6 = tr.enc(payload6)
        assertEquals("eb3f3515110702e047a6c9da4478b6ead94873c11c0f2d710ddb3f09fce024b3a58502ae3f", hex(m6.copyOfRange(8, m6.size)))
        assertArrayEquals(payload6, ti.dec(m6))
    }
}
