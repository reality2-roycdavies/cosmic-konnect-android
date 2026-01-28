package io.github.cosmickonnect.ckp

import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
// Note: Using java.security.PrivateKey/PublicKey instead of XECPrivateKey/XECPublicKey
// for compatibility with Android's Conscrypt provider
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic operations for CKP
 */
object CkpCrypto {
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val KEY_SIZE = 32
    private const val SESSION_NONCE_SIZE = 32

    private val secureRandom = SecureRandom()

    /**
     * Generate a new X25519 key pair
     */
    fun generateKeyPair(): CkpKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("X25519")
        val keyPair = keyPairGenerator.generateKeyPair()
        return CkpKeyPair(keyPair)
    }

    /**
     * Perform X25519 key exchange
     */
    fun keyExchange(privateKey: java.security.PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
        Log.d("CkpCrypto", "keyExchange: peer public key size = ${peerPublicKeyBytes.size}")

        // Validate key size
        if (peerPublicKeyBytes.size != 32) {
            Log.e("CkpCrypto", "Invalid peer public key size: ${peerPublicKeyBytes.size}, expected 32")
            throw IllegalArgumentException("Invalid X25519 public key size: ${peerPublicKeyBytes.size}")
        }

        // Convert peer public key bytes to PublicKey
        val keyFactory = KeyFactory.getInstance("X25519")

        // Try X509 encoding first, fall back to raw key if that fails
        val peerPublicKey = try {
            // Standard JDK/Conscrypt: Use X509 encoding
            val x509Bytes = wrapX25519PublicKey(peerPublicKeyBytes)
            keyFactory.generatePublic(X509EncodedKeySpec(x509Bytes))
        } catch (e: Exception) {
            // Some Android versions might accept raw key
            Log.w("CkpCrypto", "X509 encoding failed, trying raw key: ${e.message}")
            try {
                // Try with PKCS8 encoded raw key
                keyFactory.generatePublic(java.security.spec.PKCS8EncodedKeySpec(peerPublicKeyBytes))
            } catch (e2: Exception) {
                Log.e("CkpCrypto", "All key formats failed: ${e2.message}")
                throw e
            }
        }

        // Perform ECDH
        val keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)

        return keyAgreement.generateSecret()
    }

    /**
     * Wrap raw X25519 public key bytes in X509 format
     */
    private fun wrapX25519PublicKey(rawKey: ByteArray): ByteArray {
        // X509 encoding for X25519 public key
        val prefix = byteArrayOf(
            0x30, 0x2A, // SEQUENCE, length 42
            0x30, 0x05, // SEQUENCE, length 5
            0x06, 0x03, // OID, length 3
            0x2B, 0x65, 0x6E, // 1.3.101.110 (X25519)
            0x03, 0x21, // BIT STRING, length 33
            0x00 // unused bits = 0
        )
        return prefix + rawKey
    }

    /**
     * Extract raw public key bytes from X509 encoded key
     * Compatible with both XECPublicKey and Conscrypt's OpenSSLX25519PublicKey
     */
    fun extractRawPublicKey(publicKey: java.security.PublicKey): ByteArray {
        val encoded = publicKey.encoded
        // Skip the X509 header (last 32 bytes are the raw key)
        return encoded.takeLast(32).toByteArray()
    }

    /**
     * Derive the pairing key from shared secret using HKDF
     */
    fun derivePairingKey(sharedSecret: ByteArray): ByteArray {
        return hkdf(sharedSecret, null, "cosmic-konnect-v1".toByteArray(), KEY_SIZE)
    }

    /**
     * Derive session key from pairing key and nonces
     */
    fun deriveSessionKey(pairingKey: ByteArray, nonceA: ByteArray, nonceB: ByteArray): ByteArray {
        val info = nonceA + nonceB + "session".toByteArray()
        return hkdf(pairingKey, null, info, KEY_SIZE)
    }

    /**
     * Generate the 6-digit verification code from shared secret
     */
    fun generateVerificationCode(sharedSecret: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val num = ByteBuffer.wrap(hash.take(4).toByteArray()).int.toLong() and 0xFFFFFFFFL
        return String.format("%06d", num % 1_000_000)
    }

    /**
     * Generate a random session nonce
     */
    fun generateSessionNonce(): ByteArray {
        val nonce = ByteArray(SESSION_NONCE_SIZE)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * HKDF key derivation
     */
    private fun hkdf(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int
    ): ByteArray {
        // HKDF-Extract
        val actualSalt = salt ?: ByteArray(32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        // HKDF-Expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val output = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()

            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, output, offset, toCopy)
            offset += toCopy
            counter++
        }

        return output
    }

    /**
     * Encrypt data using ChaCha20-Poly1305 (fallback to AES-GCM)
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, counter: Long): ByteArray {
        // Generate nonce from counter
        val nonce = ByteArray(NONCE_SIZE)
        ByteBuffer.wrap(nonce, 4, 8).putLong(counter)

        // Use AES-GCM as ChaCha20-Poly1305 may not be available on all devices
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE * 8, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend nonce to ciphertext
        return nonce + ciphertext
    }

    /**
     * Decrypt data using ChaCha20-Poly1305 (fallback to AES-GCM)
     */
    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < NONCE_SIZE + TAG_SIZE) {
            throw IllegalArgumentException("Invalid ciphertext: too short")
        }

        val nonce = data.take(NONCE_SIZE).toByteArray()
        val ciphertext = data.drop(NONCE_SIZE).toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE * 8, nonce)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }
}

/**
 * X25519 key pair wrapper
 * Compatible with both standard JDK (XECPublicKey/XECPrivateKey) and
 * Android Conscrypt (OpenSSLX25519PublicKey/OpenSSLX25519PrivateKey)
 */
class CkpKeyPair(private val keyPair: java.security.KeyPair) {
    /**
     * Get raw public key bytes (32 bytes)
     */
    fun publicKeyBytes(): ByteArray {
        val encoded = keyPair.public.encoded
        // Skip the X509 header (last 32 bytes are the raw key)
        return encoded.takeLast(32).toByteArray()
    }

    /**
     * Perform key exchange with peer's public key
     */
    fun keyExchange(peerPublicKeyBytes: ByteArray): ByteArray {
        return CkpCrypto.keyExchange(keyPair.private, peerPublicKeyBytes)
    }
}

/**
 * Session encryption context
 */
class SessionCrypto(
    private val pairingKey: ByteArray,
    nonceA: ByteArray,
    nonceB: ByteArray
) {
    private val sessionKey = CkpCrypto.deriveSessionKey(pairingKey, nonceA, nonceB)
    private var counter = 0L

    /**
     * Encrypt a message
     */
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val encrypted = CkpCrypto.encrypt(sessionKey, plaintext, counter)
        counter++
        return encrypted
    }

    /**
     * Decrypt a message
     */
    fun decrypt(data: ByteArray): ByteArray {
        return CkpCrypto.decrypt(sessionKey, data)
    }
}

/**
 * Stored pairing information
 */
data class PairingInfo(
    val deviceId: String,
    val deviceName: String,
    val pairingKey: ByteArray,
    val pairedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingInfo) return false
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}
