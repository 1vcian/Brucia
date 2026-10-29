#ifndef MAC_FLOODING_H
#define MAC_FLOODING_H
#if !defined(LITE_VERSION)
#include "Arduino.h"
#include "lwip/prot/dhcp.h"
#include "lwip/prot/ethernet.h"
#include "lwip/prot/ip4.h"
#include "lwip/udp.h"

class MACFlooding {
private:
#define ETH_HDRLEN 14  // Ethernet header length
#define IPV4_HDRLEN 20 // IPV4 header length
#define UDP_HDRLEN 8   // UDP header length

#define MAC_ADDRESS_LENGTH 6
#define IPV4_LENGTH 4
#define ETHERNET_PROTOCOL_IPV4 0x0800
#define PACKET_LENGTH_MF 54 // ETH header + IPV4 header + some random data

    ip_hdr ipv4_pkt;
    eth_hdr eth_header;
    void send_packet();
    void show_gui();
    struct pbuf *p = nullptr;
    struct netif *netif = nullptr;
    uint8_t *ethernet_frame;
    void prepare_packet_hdr();

    // Surgically change ethernet frame to include new mac address
    void change_mac_in_packet();

    void randomize_mac(uint8_t *mac);

    // Calculate checksum for ip header
    uint16_t calculate_ip_checksum();

public:
    MACFlooding();
    ~MACFlooding();
    void setup();
    void loop();

    // Brucia: headless, bounded MAC-flooding for companion mode. The headless constructor
    // runs setup() only (no GUI, no blocking loop); run_headless floods frames with random
    // source MACs for [durationSecs] (ESC-abortable) and returns the packet count, or -1 if
    // no usable interface. AUTHORIZED USE.
    MACFlooding(bool headless);
    int run_headless(int durationSecs);
};

#endif
#endif
