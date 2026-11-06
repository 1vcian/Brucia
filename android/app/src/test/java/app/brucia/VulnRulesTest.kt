package app.brucia

import app.brucia.netscan.VulnRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accuracy of the curated phone-side vulnerability heuristics (Network Scan). These are shown to the
 * user as "likely" findings with a CVE link, so a rule must not flag a version its own text excludes.
 */
class VulnRulesTest {

    private fun ids(service: String, banner: String, version: String = ""): List<String> =
        VulnRules.match(service, banner, version).map { it.id }

    /** regreSSHion (CVE-2024-6387) affects OpenSSH 8.5-9.7; earlier 8.x must NOT be flagged for it. */
    @Test fun regreSSHionOnlyFlagsAffectedRange() {
        assertTrue(ids("ssh", "SSH-2.0-OpenSSH_8.5").contains("CVE-2024-6387"))
        assertTrue(ids("ssh", "SSH-2.0-OpenSSH_9.7").contains("CVE-2024-6387"))
        // 8.0-8.4 predate the reintroduction and are outside the stated 8.5-9.7 range.
        assertFalse(ids("ssh", "SSH-2.0-OpenSSH_8.0").contains("CVE-2024-6387"))
        assertFalse(ids("ssh", "SSH-2.0-OpenSSH_8.4").contains("CVE-2024-6387"))
        // 9.8+ is patched.
        assertFalse(ids("ssh", "SSH-2.0-OpenSSH_9.8").contains("CVE-2024-6387"))
    }

    @Test fun legacyOpenSshStillFlagged() {
        assertTrue(ids("ssh", "SSH-2.0-OpenSSH_6.6.1").contains("OpenSSH<7.4"))
        assertTrue(ids("ssh", "SSH-2.0-OpenSSH_7.2").contains("OpenSSH<7.4"))
    }

    @Test fun exposedDataStoresFlaggedByPresence() {
        assertTrue(ids("redis", "").contains("redis-open"))
        assertTrue(ids("mongodb", "").contains("mongo-open"))
        assertTrue(ids("telnet", "").contains("telnet"))
    }

    @Test fun unrelatedServiceNotFlagged() {
        assertTrue(ids("http", "Server: nginx/1.25.3").none { it == "CVE-2024-6387" })
    }
}
