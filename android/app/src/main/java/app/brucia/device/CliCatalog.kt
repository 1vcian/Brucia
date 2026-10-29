package app.brucia.device

/** One runnable device command, with a template the user can fill in. */
data class CliCommand(val template: String, val hint: String)

/** A category of device commands, mirroring Bruce's CLI groups. */
data class CliGroup(val name: String, val commands: List<CliCommand>)

/**
 * The device's command surface, grouped - so the phone shows "every choice the device
 * has" (what the serial CLI exposes). Tapping a command puts it in the console input;
 * fill the <args> and run. Menu-only attacks (deauth, BLE spam) have dedicated screens.
 */
object CliCatalog {
    val groups: List<CliGroup> = listOf(
        CliGroup("Wi-Fi", listOf(
            CliCommand("wifi on", "connect to a known network / start AP"),
            CliCommand("wifi off", "disconnect Wi-Fi"),
            CliCommand("wifi add <ssid> <password>", "remember a network"),
            CliCommand("webui", "start the Bruce web UI"),
            CliCommand("arp", "scan hosts on the LAN"),
            CliCommand("sniffer", "raw Wi-Fi sniffer"),
            CliCommand("responder", "LLMNR/NBT-NS poisoner (capture NTLM hashes)"),
        )),
        CliGroup("Sub-GHz", listOf(
            CliCommand("subghz rx", "receive / decode"),
            CliCommand("subghz tx <key> <freq> <te> <repeat>", "transmit a binary key"),
            CliCommand("subghz txp <protocol> <freq> <bits> <key> <te> <repeat>", "transmit by protocol"),
            CliCommand("subghz keeloqtx <mf> <freq> <button> <serial> <counter>", "KeeLoq transmit"),
            CliCommand("subghz scan", "scan common frequencies"),
            CliCommand("subghz list", "list saved signals"),
        )),
        CliGroup("NFC / RFID", listOf(
            CliCommand("rfid read", "read a tag"),
            CliCommand("rfid info", "tag type / UID / SAK"),
            CliCommand("rfid clone", "clone to a writable tag"),
            CliCommand("rfid write", "write a saved tag"),
            CliCommand("rfid erase", "erase a tag"),
            CliCommand("rfid save <name>", "save the read tag"),
        )),
        CliGroup("IR", listOf(
            CliCommand("ir rx", "learn a remote"),
            CliCommand("ir tx <protocol> <address> <command>", "transmit a code"),
            CliCommand("ir tx_raw <frequency> <samples>", "transmit raw samples"),
            CliCommand("ir tx_from_file <path>", "transmit a .ir file"),
        )),
        CliGroup("BadUSB", listOf(
            CliCommand("badusb run_from_file <path>", "run a DuckyScript file"),
        )),
        CliGroup("Crypto", listOf(
            CliCommand("crypto encrypt <text>", "encrypt"),
            CliCommand("crypto decrypt <text>", "decrypt"),
        )),
        CliGroup("Storage", listOf(
            CliCommand("storage list <path>", "list a directory"),
            CliCommand("storage read <path>", "print a file"),
            CliCommand("storage stat <path>", "file info"),
            CliCommand("storage free", "free space"),
            CliCommand("storage mkdir <path>", "make a directory"),
            CliCommand("storage remove <path>", "delete a file"),
        )),
        CliGroup("System", listOf(
            CliCommand("uptime", "time since boot"),
            CliCommand("date", "device date/time"),
            CliCommand("i2c", "scan the I2C bus"),
            CliCommand("free", "free memory"),
            CliCommand("display", "screenshot info"),
            CliCommand("reboot", "restart the device"),
            CliCommand("sleep", "sleep"),
            CliCommand("poweroff", "power off"),
        )),
    )
}
