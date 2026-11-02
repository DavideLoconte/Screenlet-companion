package com.davideloconte.screenlet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Manages device TLS identity credentials and short authentication string pairing verification.
 *
 * Generates and stores hardware-backed elliptic curve private keys and self-signed certificates
 * in the Android KeyStore. Provides custom SSLContext configurations for mutual authentication
 * and calculates numeric SAS verification codes for pairing confirmation.
 */
object Identity {
    private const val ALIAS = "screenlet-tls"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    val certificate: X509Certificate
        get() {
            if (!keyStore.containsAlias(ALIAS)) create()
            return keyStore.getCertificate(ALIAS) as X509Certificate
        }

    private fun create() {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=Screenlet tablet"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(spec)
            generateKeyPair()
        }
    }

    /** Initializes an SSLContext presenting the local certificate and permitting peer trust validation at application level. */
    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    fun sslContext(): SSLContext {
        certificate  // created if needed
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, null) }
        val anyPeer = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf<TrustManager>(anyPeer), null) }
    }

    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** Derives a 6-digit numeric Short Authentication String (SAS) code from mutual certificates and nonces. */
    fun pairingCode(a: ByteArray, b: ByteArray, pcNonce: ByteArray, appNonce: ByteArray): String {
        val (first, second) = if (compare(a, b) <= 0) a to b else b to a
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(first); update(second); update(pcNonce); update(appNonce)
        }.digest()
        val number = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return "%06d".format(number % 1_000_000)
    }

    /** Computes a cryptographic hash commitment over the local nonce and peer certificates. */
    fun commitment(nonce: ByteArray, appCert: ByteArray, pcCert: ByteArray): String =
        MessageDigest.getInstance("SHA-256").apply { update(nonce); update(appCert); update(pcCert) }
            .digest().joinToString("") { "%02x".format(it) }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
