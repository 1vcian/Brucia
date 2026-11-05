package app.brucia

import app.brucia.mitm.MitmCertFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MitmCaTest {

    @Test
    fun caIsSelfSignedAuthority() {
        val ca = MitmCertFactory.generateCa()
        // Self-signed: verifies with its own key.
        ca.cert.verify(ca.cert.publicKey)
        // A CA has pathLen basic constraints >= 0 (getBasicConstraints returns -1 for non-CA).
        assertTrue("CA must be a certificate authority", ca.cert.basicConstraints >= 0)
        assertTrue(ca.cert.subjectX500Principal.name.contains("Brucia MITM Root CA"))
    }

    @Test
    fun leafIsSignedByCaWithDnsSan() {
        val ca = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf("example.com", ca)

        // Signed by the CA (throws if not).
        leaf.cert.verify(ca.cert.publicKey)
        // Issuer == CA subject.
        assertEquals(ca.cert.subjectX500Principal, leaf.cert.issuerX500Principal)
        // Leaf is not itself a CA.
        assertEquals(-1, leaf.cert.basicConstraints)
        // CN carries the host.
        assertTrue(leaf.cert.subjectX500Principal.name.contains("CN=example.com"))

        // SAN contains the host as a dNSName (type 2).
        val sans = leaf.cert.subjectAlternativeNames
        assertNotNull(sans)
        val dns = sans!!.firstOrNull { (it[0] as Int) == 2 }
        assertNotNull("expected a dNSName SAN", dns)
        assertEquals("example.com", dns!![1] as String)

        // serverAuth EKU present.
        assertTrue(leaf.cert.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") == true)
    }

    @Test
    fun leafForIpUsesIpAddressSan() {
        val ca = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf("192.168.1.5", ca)
        leaf.cert.verify(ca.cert.publicKey)
        val sans = leaf.cert.subjectAlternativeNames
        assertNotNull(sans)
        // iPAddress SAN type is 7.
        val ip = sans!!.firstOrNull { (it[0] as Int) == 7 }
        assertNotNull("expected an iPAddress SAN", ip)
        assertEquals("192.168.1.5", ip!![1] as String)
    }

    @Test
    fun pemExportIsWellFormed() {
        val ca = MitmCertFactory.generateCa()
        val pem = MitmCertFactory.toPem(ca.cert)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))
    }

    @Test
    fun hostUpToSixtyFourCharactersIsTheCommonName() {
        val ca = MitmCertFactory.generateCa()
        val host = "h".repeat(55) + ".example" + "x" // 64 characters
        assertEquals(MitmCertFactory.MAX_CN_LENGTH, host.length)
        val leaf = MitmCertFactory.generateLeaf(host, ca)
        assertEquals("CN=$host", leaf.cert.subjectX500Principal.name)
    }

    @Test
    fun longerHostKeepsItsSanAndDropsTheCommonName() {
        // BouncyCastle 1.85+ refuses a CN over 64 characters; DNS names may be up to 253.
        val ca = MitmCertFactory.generateCa()
        val host = "a".repeat(60) + "." + "b".repeat(60) + ".example.com"
        val leaf = MitmCertFactory.generateLeaf(host, ca)
        leaf.cert.verify(ca.cert.publicKey)
        assertEquals("OU=Authorized testing only,O=Brucia", leaf.cert.subjectX500Principal.name)
        val dns = leaf.cert.subjectAlternativeNames!!.single { (it[0] as Int) == 2 }
        assertEquals(host, dns[1] as String)
    }

    @Test
    fun hostIsALiteralCommonNameNotParsedAsADistinguishedName() {
        // A CONNECT target is attacker-controlled text; DN separators must not add RDNs.
        val subject = MitmCertFactory.leafSubject("evil.example,O=Trusted Bank+OU=x")
        assertEquals(1, subject.rdNs.size)
        assertEquals(1, subject.rdNs[0].size())
        assertEquals("evil.example,O=Trusted Bank+OU=x", subject.rdNs[0].first.value.toString())
    }
}
