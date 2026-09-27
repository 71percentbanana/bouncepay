package com.bouncepay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * Cross-platform wire format.
 *
 * The phone signs and the settlement service verifies, and they are written in
 * different languages by different halves of this project. If they disagree by
 * one byte — key encoding, digest, or how the payload is serialised — every
 * payment fails with an unhelpful "bad signature".
 *
 * These build a packet exactly the way the app does, using only JVM crypto,
 * and write it to disk so the Node test suite can verify the very same bytes.
 * That closes the loop without needing a handset.
 */
class WireFormatTest {

    /** Mirrors DeviceKey.accountIdFor, which uses the same digest and prefix. */
    private fun fingerprint(spkiBase64: String): String {
        val raw = Base64.getDecoder().decode(spkiBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return "bp_" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a byte with the high bit set formats as two hex digits`() {
        // Kotlin's "%02x".format on a widened Int would sign-extend 0xFF into
        // "ffffffff" and silently produce a different account id on some keys.
        val b: Byte = -1
        assertEquals("ff", "%02x".format(b))
        assertEquals(19, fingerprint(Base64.getEncoder().encodeToString(ByteArray(32) { -1 })).length)
    }

    @Test
    fun `a packet built the way the app builds it verifies with JVM crypto`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()

        val spki = Base64.getEncoder().encodeToString(pair.public.encoded)
        val payerId = fingerprint(spki)

        // Exactly the concatenation order used in Packet.create.
        val payload = buildString {
            append("{")
            append("\"v\":").append(1).append(",")
            append("\"txId\":\"").append(UUID.randomUUID()).append("\",")
            append("\"nonce\":\"").append("a1b2c3d4e5f60718293a4b5c").append("\",")
            append("\"amountPaise\":").append(500_00).append(",")
            append("\"payerId\":\"").append(payerId).append("\",")
            append("\"payeeId\":\"").append("campus-stationery").append("\",")
            append("\"createdAt\":").append(System.currentTimeMillis())
            append("}")
        }

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val sig = Base64.getEncoder().encodeToString(signer.sign())

        // Verifies locally, which is what EmbeddedBank relies on.
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        assertTrue("signature must verify with the matching public key",
            verifier.verify(Base64.getDecoder().decode(sig)))

        // Hand the exact bytes to the Node suite, which verifies them with a
        // completely independent implementation.
        val packetJson = """{"payload":${quote(payload)},"sig":"$sig","payerPubKey":"$spki","hops":["$payerId"]}"""
        val out = File(System.getProperty("bouncepay.wireOut") ?: "build/wire-sample.json")
        out.parentFile?.mkdirs()
        out.writeText(packetJson)

        assertTrue("payload must be compact JSON with no spaces", !payload.contains(", "))
        assertEquals("payerId must be derived from the signing key", payerId, fingerprint(spki))
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
