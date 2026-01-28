package io.github.cosmickonnect.protocol.tls

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*

/**
 * Handles TLS certificate generation and connection upgrade.
 * KDE Connect uses self-signed certificates for device identity.
 */
class TlsHelper(private val context: Context) {

    companion object {
        private const val TAG = "TlsHelper"
        private const val KEYSTORE_FILE = "keystore.p12"
        private const val KEYSTORE_PASSWORD = "cosmic-konnect"
        private const val KEY_ALIAS = "device-key"
        private const val CERT_VALIDITY_DAYS = 3650 // 10 years

        init {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private var keyStore: KeyStore? = null
    private var sslContext: SSLContext? = null

    init {
        loadOrCreateKeyStore()
        initSslContext()
    }

    private fun loadOrCreateKeyStore() {
        val keystoreFile = File(context.filesDir, KEYSTORE_FILE)

        keyStore = KeyStore.getInstance("PKCS12")

        if (keystoreFile.exists()) {
            try {
                FileInputStream(keystoreFile).use { fis ->
                    keyStore!!.load(fis, KEYSTORE_PASSWORD.toCharArray())
                }
                Log.d(TAG, "Loaded existing keystore")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load keystore, creating new one: ${e.message}")
            }
        }

        // Create new keystore with self-signed certificate
        keyStore!!.load(null, KEYSTORE_PASSWORD.toCharArray())

        val keyPair = generateKeyPair()
        val certificate = generateSelfSignedCertificate(keyPair)

        keyStore!!.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(certificate)
        )

        // Save keystore
        FileOutputStream(keystoreFile).use { fos ->
            keyStore!!.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }

        Log.d(TAG, "Created new keystore with self-signed certificate")
    }

    private fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val deviceId = getDeviceId()
        val subject = X500Name("CN=$deviceId, O=Cosmic Konnect, OU=Device Certificate")

        val now = Date()
        val notBefore = Date(now.time - 24 * 60 * 60 * 1000) // Yesterday
        val notAfter = Date(now.time + CERT_VALIDITY_DAYS.toLong() * 24 * 60 * 60 * 1000)

        val serial = BigInteger(64, SecureRandom())

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider())
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(certBuilder.build(signer))
    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("cosmic_konnect", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "")
    }

    private fun initSslContext() {
        // Key manager (our certificate)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray())

        // Trust manager (accept all certificates - KDE Connect uses certificate pinning separately)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext!!.init(keyManagerFactory.keyManagers, arrayOf(trustManager), SecureRandom())
    }

    /**
     * Upgrade a plain socket to TLS.
     *
     * In KDE Connect protocol:
     * - TCP initiator (outgoing connection) = TLS SERVER
     * - TCP acceptor (incoming connection) = TLS CLIENT
     *
     * This is counterintuitive but that's how KDE Connect works.
     *
     * @param socket The plain TCP socket to upgrade
     * @param hostname The hostname for SNI (if client mode)
     * @param isInitiator True if we initiated the TCP connection (makes us TLS server)
     */
    fun upgradeToTls(socket: Socket, hostname: String, isInitiator: Boolean = true): SSLSocket {
        val sslSocketFactory = sslContext!!.socketFactory

        val sslSocket = sslSocketFactory.createSocket(
            socket,
            hostname,
            socket.port,
            true
        ) as SSLSocket

        // In KDE Connect: TCP initiator = TLS server, TCP acceptor = TLS client
        // So if we initiated the connection (isInitiator=true), we are the TLS server
        sslSocket.useClientMode = !isInitiator
        sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")

        Log.i(TAG, "Starting TLS handshake as ${if (isInitiator) "server" else "client"} with $hostname")

        // Perform handshake
        sslSocket.startHandshake()

        Log.i(TAG, "TLS handshake completed with $hostname")
        return sslSocket
    }

    fun createServerSocket(port: Int): SSLServerSocket {
        val sslServerSocketFactory = sslContext!!.serverSocketFactory
        val serverSocket = sslServerSocketFactory.createServerSocket(port) as SSLServerSocket

        serverSocket.useClientMode = false
        serverSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")

        return serverSocket
    }

    fun getCertificate(): X509Certificate? {
        return keyStore?.getCertificate(KEY_ALIAS) as? X509Certificate
    }
}
