# Ethernet

Wired-network tools for the optional W5500 Ethernet add-on. Gated on `caps.eth`; **the
W5500 is not fitted on the T-Embed CC1101**, so this page is hidden on that board.

> For authorized security research and education only. DHCP starvation and MAC flooding
> are dual-use - only run them against a switch/network you own or are explicitly
> permitted to test.

## Status
Refreshes and shows the wired link state: link up/down and the current IP address.

## Configuration
Sends `eth_config dhcp` or `eth_config static <ip> <gw> <mask> [dns]` (a blank field means
DHCP). The device brings the W5500 up with those settings, reports the resulting link and IP,
and stops the interface again: it is a link test, the settings are not kept afterwards.

## DHCP starvation
Exhausts the DHCP pool on the wired LAN (`eth_dhcp_starve`), bounded to a short run.
Authorized testing only.

## MAC flooding
Floods the switch's CAM table with bogus MAC addresses (`eth_mac_flood`), bounded to a
short run. Authorized testing only.

---
Back to [[Features]].
