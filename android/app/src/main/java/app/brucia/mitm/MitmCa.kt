package app.brucia.mitm

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Persistent, Context-aware wrapper around [MitmCertFactory]. Generates the "Brucia
 * MITM" root CA on first use, stores it in app-private storage, exports the public
 * certificate for installation on an authorized target device, and builds a cached
 * per-host server [SSLContext] whose leaf certificate is signed by the CA.
 */
object MitmCa {

    private const val DIR = "mitm"
    private const val CA_STORE = "brucia_mitm_ca.p12"
    private const val EXPORT_NAME = "brucia-mitm-ca.crt"
    private val STORE_PASS = "brucia-mitm".toCharArray()

    @Volatile
    private var ca: MitmCertFactory.CertAndKey? = null

    // Per-host server SSLContexts (leaf cert signed by the CA), cached for reuse.
    private val serverContexts = ConcurrentHashMap<String, SSLContext>()

    private fun storeFile(ctx: Context): File =
        File(ctx.filesDir, "$DIR/$CA_STORE").also { it.parentFile?.mkdirs() }

    /** Load the CA from disk, or generate and persist it on first use. */
    @Synchronized
    fun ensureCa(ctx: Context): MitmCertFactory.CertAndKey {
        ca?.let { return it }
        val file = storeFile(ctx)
        val loaded = if (file.exists()) runCatching { load(file) }.getOrNull() else null
        val result = loaded ?: MitmCertFactory.generateCa().also { save(it, file) }
        ca = result
        return result
    }

    private fun load(file: File): MitmCertFactory.CertAndKey {
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, STORE_PASS) }
        val key = ks.getKey("ca", STORE_PASS) as java.security.PrivateKey
        val cert = ks.getCertificate("ca") as X509Certificate
        val pub = cert.publicKey
        return MitmCertFactory.CertAndKey(cert, java.security.KeyPair(pub, key))
    }

    private fun save(ca: MitmCertFactory.CertAndKey, file: File) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("ca", ca.keyPair.private, STORE_PASS, arrayOf(ca.cert))
        file.outputStream().use { ks.store(it, STORE_PASS) }
    }

    /** PEM of the CA certificate. */
    fun caPem(ctx: Context): String = MitmCertFactory.toPem(ensureCa(ctx).cert)

    /** Write the CA public certificate (.crt, PEM) to shareable storage and return it. */
    fun exportCaCert(ctx: Context): File {
        val dir = ctx.getExternalFilesDir("exports") ?: ctx.filesDir
        val out = File(dir, EXPORT_NAME)
        out.writeText(caPem(ctx))
        return out
    }

    /** True once the CA has been generated (file present). */
    fun caExists(ctx: Context): Boolean = ca != null || storeFile(ctx).exists()

    /**
     * Server-side [SSLContext] presenting a leaf for [host] (signed by the CA),
     * cached per host so the leaf is generated at most once.
     */
    fun serverContextFor(ctx: Context, host: String): SSLContext =
        serverContexts.getOrPut(host) { buildServerContext(ctx, host) }

    private fun buildServerContext(ctx: Context, host: String): SSLContext {
        val root = ensureCa(ctx)
        val leaf = MitmCertFactory.generateLeaf(host, root)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leaf", leaf.keyPair.private, STORE_PASS, arrayOf(leaf.cert, root.cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, STORE_PASS)
        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, null, java.security.SecureRandom())
        return sc
    }

    /** Clear the in-memory leaf cache (e.g. on proxy restart). */
    fun clearLeafCache() = serverContexts.clear()
}
