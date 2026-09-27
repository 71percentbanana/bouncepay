package com.bouncepay.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The device's payment identity.
 *
 * An ECDSA P-256 key pair generated inside the Android KeyStore. The private
 * key is never readable by this app — signing happens inside the keystore —
 * so a packet can be authorised on a phone that is offline without the secret
 * ever being exposed to the relays that will carry it.
 *
 * P-256 is chosen because it is hardware-backed on essentially every modern
 * Android device, and because `SHA256withECDSA` over a DER SPKI public key is
 * verifiable by the settlement service with no custom encoding on either side.
 */
object DeviceKey {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bouncepay.device.v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** Creates the key pair on first use. Safe to call repeatedly. */
    fun ensureKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // A payment must be signable while the screen is off and the
                // phone is in someone's pocket, so this key is not gated on
                // user authentication. A production build would gate it on a
                // biometric prompt at payment time instead.
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun privateKey(): PrivateKey {
        ensureKey()
        return keyStore.getKey(KEY_ALIAS, null) as PrivateKey
    }

    private fun publicKey(): PublicKey {
        ensureKey()
        return keyStore.getCertificate(KEY_ALIAS).publicKey
    }

    /** Base64 DER SPKI — exactly what the settlement service expects. */
    fun publicKeySpki(): String =
        Base64.getEncoder().encodeToString(publicKey().encoded)

    /**
     * Stable short identifier derived from the public key.
     *
     * The service derives the same value independently and refuses any packet
     * whose payerId does not match the key that signed it, so a relay cannot
     * present someone else's account as the payer.
     */
    fun accountId(): String = accountIdFor(publicKeySpki())

    fun accountIdFor(spkiBase64: String): String {
        val raw = Base64.getDecoder().decode(spkiBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return "bp_" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Signs the exact UTF-8 bytes of [payload]; returns base64 DER. */
    fun sign(payload: String): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey())
            update(payload.toByteArray(Charsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * Verifies [payload] against any P-256 key — a payer's, for the in-app
     * bank fallback, or the bank's, for a receipt that arrived over the mesh.
     * Pure JVM, so it also runs in unit tests.
     */
    fun verify(payload: String, sigBase64: String, spkiBase64: String): Boolean = try {
        val keyBytes = Base64.getDecoder().decode(spkiBase64)
        val spec = java.security.spec.X509EncodedKeySpec(keyBytes)
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(spec)
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(sigBase64))
        }
    } catch (_: Exception) {
        false
    }
}
