package app.brucia.netscan

/**
 * Curated, offline heuristic rules that flag likely vulnerabilities from a service banner/version.
 * These are high-signal pointers (with a CVE/details link), NOT a full CVE feed or a guarantee - they
 * say "this version is commonly associated with X, worth checking". All matching is phone-side.
 */
object VulnRules {

    private data class Rule(
        val service: String?,                 // match this service name, or null for any
        val needle: Regex,                    // matched against banner + version (case-insensitive)
        val hit: VulnHit,
    )

    private fun cve(id: String) = "https://nvd.nist.gov/vuln/detail/$id"

    private val RULES: List<Rule> = listOf(
        // --- Apache httpd ---
        Rule("http", Regex("(?i)Apache/2\\.4\\.(49|50)\\b"),
            VulnHit("CVE-2021-41773", "Apache 2.4.49/2.4.50 path traversal + RCE", "critical", cve("CVE-2021-41773"))),
        Rule(null, Regex("(?i)Apache/2\\.2\\."),
            VulnHit("EOL", "Apache 2.2.x is end-of-life (many known CVEs)", "high", "https://httpd.apache.org/security/vulnerabilities_22.html")),
        // --- OpenSSH ---
        Rule("ssh", Regex("(?i)OpenSSH_([0-6]\\.|7\\.[0-3])\\b"),
            VulnHit("OpenSSH<7.4", "OpenSSH < 7.4: user-enum / legacy CVEs", "medium", "https://www.openssh.com/security.html")),
        Rule("ssh", Regex("(?i)OpenSSH_(8\\.[5-9]|9\\.[0-7])\\b"),
            VulnHit("CVE-2024-6387", "OpenSSH 'regreSSHion' RCE affects 8.5-9.7", "high", cve("CVE-2024-6387"))),
        // --- vsftpd backdoor ---
        Rule("ftp", Regex("(?i)vsftpd 2\\.3\\.4"),
            VulnHit("CVE-2011-2523", "vsftpd 2.3.4 has a known backdoor", "critical", cve("CVE-2011-2523"))),
        Rule("ftp", Regex("(?i)220.*ProFTPD 1\\.3\\.[0-5]\\b"),
            VulnHit("ProFTPD", "ProFTPD <=1.3.5 mod_copy RCE (CVE-2015-3306)", "high", cve("CVE-2015-3306"))),
        // --- Samba / SMB ---
        Rule("smb", Regex("(?i)Samba 3\\.|Samba 4\\.[0-5]\\b"),
            VulnHit("CVE-2017-7494", "Samba <4.6.4 'SambaCry' RCE", "critical", cve("CVE-2017-7494"))),
        // --- nginx ---
        Rule("http", Regex("(?i)nginx/1\\.(1[0-9]|20)\\.0\\b"),
            VulnHit("nginx", "Check nginx version against known CVEs", "info", "https://nginx.org/en/security_advisories.html")),
        // --- Exposed data stores / mgmt (open to the LAN at all is noteworthy) ---
        Rule("redis", Regex(".*"),
            VulnHit("redis-open", "Redis reachable - verify it requires auth (unauth = RCE)", "high", "https://redis.io/docs/management/security/")),
        Rule("mongodb", Regex(".*"),
            VulnHit("mongo-open", "MongoDB reachable - verify auth is enabled", "high", "https://www.mongodb.com/docs/manual/administration/security-checklist/")),
        Rule("mysql", Regex(".*"),
            VulnHit("mysql-open", "MySQL reachable on the LAN - check access controls", "medium", "https://dev.mysql.com/doc/refman/8.0/en/security.html")),
        Rule("telnet", Regex(".*"),
            VulnHit("telnet", "Telnet is cleartext - credentials are exposed", "high", "https://www.us-cert.gov/ncas/alerts/TA14-013A")),
        Rule("rdp", Regex(".*"),
            VulnHit("rdp-open", "RDP exposed - ensure NLA + patches (BlueKeep CVE-2019-0708)", "high", cve("CVE-2019-0708"))),
        Rule("vnc", Regex(".*"),
            VulnHit("vnc-open", "VNC exposed - verify a strong password / no null auth", "medium", "https://www.realvnc.com/en/connect/docs/security.html")),
        Rule("tr069", Regex(".*"),
            VulnHit("tr069", "TR-069 (7547) exposed - router mgmt, past mass-exploit target", "medium", cve("CVE-2016-10372"))),
    )

    /** Return every rule that matches this service's banner/version. */
    fun match(service: String, banner: String, version: String): List<VulnHit> {
        val hay = (banner + "\n" + version)
        return RULES.filter { r ->
            (r.service == null || r.service == service) && r.needle.containsMatchIn(hay)
        }.map { it.hit }.distinctBy { it.id }
    }
}
