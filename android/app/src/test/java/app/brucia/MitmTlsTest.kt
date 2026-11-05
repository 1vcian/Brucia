package app.brucia

import app.brucia.mitm.MitmCa
import app.brucia.mitm.MitmCertFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory

/**
 * The MITM TLS path end to end, in memory (two SSLEngines, no sockets): the server side is
 * MitmCa.serverContext with BouncyCastle-built certificates, the client is the JDK TLS stack
 * with HTTPS endpoint identification and only the Brucia CA as trust anchor.
 */
class MitmTlsTest {

    private fun clientEngine(trusted: X509Certificate, host: String): SSLEngine {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", trusted)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        return ctx.createSSLEngine(host, 443).apply {
            useClientMode = true
            sslParameters = sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                if (!host.first().isDigit()) serverNames = listOf(SNIHostName(host))
            }
        }
    }

    /** Runs the handshake plus one application record each way; throws SSLException on failure. */
    private fun handshake(client: SSLEngine, server: SSLEngine) {
        server.useClientMode = false
        val size = maxOf(client.session.packetBufferSize, server.session.packetBufferSize) * 4
        val c2s = ByteBuffer.allocate(size)
        val s2c = ByteBuffer.allocate(size)
        val clientIn = ByteBuffer.allocate(size)
        val serverIn = ByteBuffer.allocate(size)
        val empty = ByteBuffer.allocate(0)
        client.beginHandshake()
        server.beginHandshake()
        fun step(engine: SSLEngine, out: ByteBuffer, inbound: ByteBuffer, appIn: ByteBuffer): Boolean {
            var progressed = false
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> { engine.wrap(empty, out); progressed = true }
                HandshakeStatus.NEED_UNWRAP -> {
                    inbound.flip()
                    val r = engine.unwrap(inbound, appIn)
                    inbound.compact()
                    progressed = r.bytesConsumed() > 0 || r.handshakeStatus != HandshakeStatus.NEED_UNWRAP
                }
                HandshakeStatus.NEED_TASK -> { while (true) engine.delegatedTask?.run() ?: break; progressed = true }
                else -> {}
            }
            return progressed
        }
        var rounds = 0
        while (client.handshakeStatus != HandshakeStatus.NOT_HANDSHAKING ||
            server.handshakeStatus != HandshakeStatus.NOT_HANDSHAKING
        ) {
            check(++rounds < 1000) { "handshake did not converge" }
            val a = step(client, c2s, s2c, clientIn)
            val b = step(server, s2c, c2s, serverIn)
            check(a || b) { "handshake stalled: ${client.handshakeStatus}/${server.handshakeStatus}" }
        }
        // One record each way over the established session.
        val request = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        client.wrap(ByteBuffer.wrap(request), c2s)
        c2s.flip(); server.unwrap(c2s, serverIn); c2s.compact()
        serverIn.flip()
        assertArrayEquals(request, ByteArray(serverIn.remaining()).also { serverIn.get(it) })
    }

    private fun peerChain(client: SSLEngine) = client.session.peerCertificates.map { it as X509Certificate }

    @Test fun clientTrustingTheCaAcceptsTheLeafForItsHost() {
        val ca = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf("example.com", ca)
        val client = clientEngine(ca.cert, "example.com")
        handshake(client, MitmCa.serverContext(leaf, ca).createSSLEngine())
        assertEquals(listOf(leaf.cert, ca.cert), peerChain(client))
    }

    @Test fun hostLongerThanACommonNameIsServedThroughTheSan() {
        // 4 labels, 98 characters: valid DNS name, does not fit the 64-character CN.
        val host = "a".repeat(30) + "." + "b".repeat(30) + "." + "c".repeat(30) + ".example"
        assertTrue(host.length > MitmCertFactory.MAX_CN_LENGTH)
        val ca = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf(host, ca)
        val client = clientEngine(ca.cert, host)
        handshake(client, MitmCa.serverContext(leaf, ca).createSSLEngine())
        assertEquals(leaf.cert, peerChain(client)[0])
    }

    @Test fun ipLiteralIsVerifiedAgainstTheIpSan() {
        val ca = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf("192.168.1.5", ca)
        val client = clientEngine(ca.cert, "192.168.1.5")
        handshake(client, MitmCa.serverContext(leaf, ca).createSSLEngine())
        assertEquals(leaf.cert, peerChain(client)[0])
    }

    @Test fun clientTrustingAnotherCaRejectsTheLeaf() {
        val ca = MitmCertFactory.generateCa()
        val other = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf("example.com", ca)
        try {
            handshake(clientEngine(other.cert, "example.com"), MitmCa.serverContext(leaf, ca).createSSLEngine())
            fail("handshake must fail without the issuing CA")
        } catch (_: SSLException) {
        }
    }

    @Test fun leafForAnotherHostFailsHostnameVerification() {
        val ca = MitmCertFactory.generateCa()
        val leaf = MitmCertFactory.generateLeaf("example.com", ca)
        try {
            handshake(clientEngine(ca.cert, "other.example"), MitmCa.serverContext(leaf, ca).createSSLEngine())
            fail("handshake must fail for a host the leaf does not name")
        } catch (_: SSLException) {
        }
    }

    @Test fun caSurvivesThePkcs12StoreRoundTrip() {
        val ca = MitmCertFactory.generateCa()
        val file = Files.createTempFile("brucia-mitm-ca", ".p12").toFile()
        try {
            MitmCa.save(ca, file)
            val loaded = MitmCa.load(file)
            assertArrayEquals(ca.cert.encoded, loaded.cert.encoded)
            assertArrayEquals(ca.keyPair.private.encoded, loaded.keyPair.private.encoded)
            // A leaf signed by the reloaded key chains to the original certificate.
            val leaf = MitmCertFactory.generateLeaf("example.com", loaded)
            leaf.cert.verify(ca.cert.publicKey)
            val client = clientEngine(ca.cert, "example.com")
            handshake(client, MitmCa.serverContext(leaf, loaded).createSSLEngine())
            assertEquals(leaf.cert, peerChain(client)[0])
        } finally {
            file.delete()
        }
    }
}
