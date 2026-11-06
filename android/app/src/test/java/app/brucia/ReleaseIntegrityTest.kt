package app.brucia

import app.brucia.update.AssetIntegrity
import app.brucia.update.DownloadIO
import app.brucia.update.IntegrityException
import app.brucia.update.ReleaseCatalog
import app.brucia.update.ReleaseUpdateState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Same fixtures as the integrity cases in tools/test_site_release.cjs.
class ReleaseIntegrityTest {
    private val body = "abc".toByteArray()
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val firmware = "brucia-t-embed-cc1101.bin"

    private fun asset(name: String, size: Any? = 3, digest: Any? = "sha256:$abc"): JSONObject {
        val json = JSONObject().put("name", name)
            .put("browser_download_url", "${BrandLinks.DOWNLOAD_PREFIX}v0.2/$name")
        if (size != null) json.put("size", size)
        if (digest != null) json.put("digest", digest)
        return json
    }
    private fun parse(vararg assets: JSONObject) = ReleaseCatalog.parse(JSONObject().put("tag_name", "v0.2")
        .put("assets", JSONArray(assets.toList())).toString())

    private fun copy(integrity: AssetIntegrity, bytes: ByteArray = body, expected: Long = bytes.size.toLong()): ByteArray {
        val out = ByteArrayOutputStream()
        DownloadIO.copyVerified(ByteArrayInputStream(bytes), out, expected, 1024, integrity, { false }, { _, _ -> })
        return out.toByteArray()
    }

    @Test fun matchingSizeAndDigestAreParsedAndVerified() {
        val release = parse(asset("Brucia.apk"), asset(firmware))
        val board = release.boards.single()
        assertEquals(AssetIntegrity(3, abc), board.integrity)
        assertEquals(AssetIntegrity(3, abc), release.apkIntegrity)
        assertTrue(board.integrity.verified)
        for (expected in listOf(3L, -1L)) assertArrayEquals(body, copy(board.integrity, expected = expected))
        val state = ReleaseUpdateState(release = release)
        assertEquals(board.integrity, state.flashIntegrity)
        assertEquals(board.url, state.flashUrl)
    }

    @Test fun sizeMismatchIsAHardFailure() {
        val listed = parse(asset(firmware, size = 4)).boards.single().integrity
        for (expected in listOf(3L, -1L)) {
            try { copy(listed, expected = expected); fail("Accepted size mismatch ($expected)") }
            catch (e: IntegrityException) { assertTrue(e.message!!, e.message!!.startsWith("Size mismatch")) }
        }
    }

    @Test fun digestMismatchIsAHardFailure() {
        val listed = parse(asset(firmware, digest = "sha256:" + "0".repeat(64))).boards.single().integrity
        try { copy(listed); fail("Accepted digest mismatch") }
        catch (e: IntegrityException) { assertTrue(e.message!!, e.message!!.startsWith("SHA-256 mismatch")) }
        try { listed.check("abd".toByteArray()); fail("Accepted digest mismatch") } catch (_: IntegrityException) { }
    }

    @Test fun absentDigestOrSizeIsAllowedButUnverified() {
        for (fixture in listOf(asset(firmware, digest = null), asset(firmware, digest = JSONObject.NULL),
                asset(firmware, size = null, digest = null), asset(firmware, size = JSONObject.NULL, digest = JSONObject.NULL))) {
            val integrity = parse(fixture).boards.single().integrity
            assertFalse(integrity.verified)
            assertTrue(integrity.label.startsWith("unverified"))
            assertArrayEquals(body, copy(integrity))
        }
        // An unverified asset still has its listed size enforced.
        val sized = parse(asset(firmware, digest = null)).boards.single().integrity
        try { copy(sized, "abcd".toByteArray()); fail("Accepted wrong size") } catch (_: IntegrityException) { }
        assertEquals(AssetIntegrity.NONE, ReleaseUpdateState().flashIntegrity)
    }

    @Test fun malformedDigestOrSizeRejectsTheAssetInsteadOfDowngrading() {
        for (digest in listOf<Any>("sha256:" + abc.uppercase(), "SHA256:$abc", "sha256:" + abc.dropLast(1),
                "sha256:${abc}0", "sha512:$abc", abc, " sha256:$abc", "sha256:$abc\n", "", 42, JSONArray().put("sha256:$abc"))) {
            val release = parse(asset("Brucia.apk", digest = digest), asset(firmware, digest = digest))
            assertNull("digest $digest", release.apk)
            assertTrue("digest $digest", release.boards.isEmpty())
        }
        for (size in listOf<Any>(0, -3, "3", true, ReleaseCatalog.FIRMWARE_MAX_BYTES + 1)) {
            assertTrue("size $size", parse(asset(firmware, size = size)).boards.isEmpty())
        }
        // Raw number spellings: JavaScript's JSON.parse yields the same accept/reject decision.
        for ((text, accepted) in listOf("1.5" to false, "1e400" to false, "-0" to false, "3.0" to true, "0.3e1" to true)) {
            val raw = ReleaseCatalog.parse("{\"tag_name\":\"v0.2\",\"assets\":[" +
                asset(firmware, size = null).toString().dropLast(1) + ",\"size\":$text}]}")
            assertEquals("size $text", accepted, raw.boards.isNotEmpty())
            if (accepted) assertEquals(3L, raw.boards.single().integrity.size)
        }
        val apkLimit = parse(asset("Brucia.apk", size = ReleaseCatalog.FIRMWARE_MAX_BYTES + 1, digest = null))
        assertEquals(ReleaseCatalog.FIRMWARE_MAX_BYTES + 1, apkLimit.apkIntegrity.size)
        assertNull(parse(asset("Brucia.apk", size = ReleaseCatalog.APK_MAX_BYTES + 1)).apk)
        // A rejected asset still counts for ambiguity: the other copy is never picked silently.
        try { parse(asset(firmware, digest = "md5:x"), asset("Bruce-lilygo-t-embed-cc1101.bin")); fail("Ambiguous board accepted") }
        catch (_: IllegalArgumentException) { }
        try { parse(asset("a.apk", size = 0), asset("b.apk")); fail("Ambiguous APK accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
