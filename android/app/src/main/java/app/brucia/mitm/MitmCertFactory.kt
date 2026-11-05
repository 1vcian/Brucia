package app.brucia.mitm

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Pure (Context-free) X.509 factory for the on-phone MITM proxy. It builds a
 * "Brucia MITM" root CA and per-host leaf certificates signed by it, using
 * BouncyCastle directly. The provider is instantiated locally and handed to the
 * builders; it is never registered via Security.addProvider, so it does not clash
 * with the trimmed "BC" provider that ships inside Android.
 *
 * AUTHORIZED USE ONLY. A root CA installed on a device lets its owner decrypt that
 * device's own HTTPS traffic for testing. Install it only on devices you own or are
 * explicitly authorized to test.
 */
object MitmCertFactory {

    private val bc = BouncyCastleProvider()
    private val rng = SecureRandom()

    const val CA_SUBJECT = "CN=Brucia MITM Root CA, O=Brucia, OU=Authorized testing only"

    /** RFC 5280 ub-common-name; BouncyCastle 1.85+ rejects a longer CN when building a name. */
    const val MAX_CN_LENGTH = 64

    /** Leaf subject when the host does not fit in a CN; the host is still in the SAN. */
    const val LONG_HOST_SUBJECT = "O=Brucia, OU=Authorized testing only"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val CA_VALIDITY_MS = 3650L * DAY_MS   // ~10 years
    private const val LEAF_VALIDITY_MS = 398L * DAY_MS  // under the 398-day browser cap

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rng) }.generateKeyPair()

    private fun serial(): BigInteger = BigInteger(159, rng)

    /** Generate a self-signed root CA certificate and its key pair. */
    fun generateCa(): CertAndKey {
        val kp = rsaKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - DAY_MS)
        val notAfter = Date(now + CA_VALIDITY_MS)
        val dn = X500Name(CA_SUBJECT)
        val extUtils = JcaX509ExtensionUtils()
        val builder = JcaX509v3CertificateBuilder(dn, serial(), notBefore, notAfter, dn, kp.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
            )
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(kp.public),
            )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(kp.private)
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
        cert.verify(kp.public)
        return CertAndKey(cert, kp)
    }

    /**
     * Generate a leaf server certificate for [host], signed by the CA. The host is
     * placed in the CN and in a Subject Alternative Name (dNSName, or iPAddress when
     * [host] is a literal IPv4/IPv6 address) so modern TLS stacks accept it.
     */
    fun generateLeaf(host: String, ca: CertAndKey): CertAndKey {
        val kp = rsaKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - DAY_MS)
        val notAfter = Date(now + LEAF_VALIDITY_MS)
        // Use the CA subject's exact DER encoding so the leaf's issuer matches it byte for
        // byte (a string round-trip would reorder the RDNs and break chain building).
        val issuer = X500Name.getInstance(ca.cert.subjectX500Principal.encoded)
        val subject = leafSubject(host)
        val extUtils = JcaX509ExtensionUtils()
        val sanType = if (isIpLiteral(host)) GeneralName.iPAddress else GeneralName.dNSName
        val san = GeneralNames(GeneralName(sanType, host))
        val builder = JcaX509v3CertificateBuilder(issuer, serial(), notBefore, notAfter, subject, kp.public)
            .addExtension(Extension.basicConstraints, false, BasicConstraints(false))
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            .addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            .addExtension(Extension.subjectAlternativeName, false, san)
            .addExtension(
                Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(kp.public),
            )
            .addExtension(
                Extension.authorityKeyIdentifier, false,
                extUtils.createAuthorityKeyIdentifier(ca.cert),
            )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(ca.keyPair.private)
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
        cert.verify(ca.cert.publicKey)
        return CertAndKey(cert, kp)
    }

    /**
     * CN=[host] built as a literal attribute value (no DN string parsing, so ',', '+' or '='
     * in a CONNECT target cannot add RDNs). A host longer than [MAX_CN_LENGTH] (DNS allows
     * 253) gets [LONG_HOST_SUBJECT] instead: TLS clients match the SAN, not the CN.
     */
    internal fun leafSubject(host: String): X500Name =
        if (host.length <= MAX_CN_LENGTH) {
            X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, host).build()
        } else {
            X500Name(LONG_HOST_SUBJECT)
        }

    /** PEM encoding of a certificate (for export / inspection). */
    fun toPem(cert: X509Certificate): String {
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    data class CertAndKey(val cert: X509Certificate, val keyPair: KeyPair)
}
