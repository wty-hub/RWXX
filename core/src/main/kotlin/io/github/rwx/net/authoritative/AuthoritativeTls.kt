package io.github.rwx.net.authoritative

import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Explicit certificate pinning: the expected public fingerprint is shared out of band. */
object AuthoritativeTls {
    fun serverContext(keyStore: InputStream, password: CharArray): SSLContext {
        val keys = KeyStore.getInstance("PKCS12").apply { load(keyStore, password) }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        return SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, SecureRandom()) }
    }

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun clientContext(expectedFingerprint: String): SSLContext {
        val normalized = expectedFingerprint.replace(":", "").lowercase()
        require(normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Expected a SHA-256 server certificate fingerprint"
        }
        val pin = normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not used by this protocol")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
                leaf.checkValidity()
                val received = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(pin, received)) throw CertificateException("Server certificate fingerprint mismatch")
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    }
}
