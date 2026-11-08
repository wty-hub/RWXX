package io.github.rwx.authoritative

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.security.auth.x500.X500Principal

/** A pinned room leaf certificate, using only the JRE's public cryptography APIs.
 * DER is the fixed Certificate/TBSCertificate container from RFC 5280 section 4.1;
 * key generation and ECDSA signing are performed by JCA, never by this encoder.
 * This works in the packaged runtime, which has neither keytool nor an external JDK.
 */
internal object AuthoritativeRoomCertificate {
    data class Generated(val pkcs12: ByteArray, val certificate: X509Certificate) {
        override fun toString(): String = "Generated(private room key store)"
    }

    fun generate(password: CharArray, now: Instant = Instant.now()): Generated {
        require(password.size >= 16)
        val random = SecureRandom()
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val algorithm = der(0x30, der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02)))
        val name = X500Principal("CN=RWXX authoritative room").encoded
        val tbs = der(0x30,
            der(0xa0, der(0x02, byteArrayOf(2))),
            der(0x02, BigInteger(159, random).max(BigInteger.ONE).toByteArray()),
            algorithm, name,
            der(0x30, time(now.minusSeconds(300)), time(now.plusSeconds(365L * 86400))),
            name, keys.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private, random); update(tbs); sign() }
        val encoded = der(0x30, tbs, algorithm, der(0x03, byteArrayOf(0) + signature))
        val certificate = encoded.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        certificate.verify(keys.public)
        certificate.checkValidity(java.util.Date.from(now))
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("room", keys.private, password, arrayOf(certificate))
        }
        val output = ByteArrayOutputStream()
        store.store(output, password)
        return Generated(output.toByteArray(), certificate)
    }

    private fun time(instant: Instant): ByteArray {
        val year = instant.atZone(ZoneOffset.UTC).year
        val utc = year in 1950..2049
        val value = DateTimeFormatter.ofPattern(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(instant).toByteArray(Charsets.US_ASCII)
        return der(if (utc) 0x17 else 0x18, value)
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val length = parts.sumOf { it.size }
        return ByteArrayOutputStream().apply {
            write(tag)
            if (length < 128) write(length) else {
                val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
                write(0x80 or bytes.size); write(bytes)
            }
            parts.forEach(::write)
        }.toByteArray()
    }
}
