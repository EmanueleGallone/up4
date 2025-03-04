#!/usr/bin/python3
# SPDX-FileCopyrightText: 2020 Open Networking Foundation <info@opennetworking.org>
# SPDX-License-Identifier: Apache-2.0

import argparse
import signal
from ipaddress import IPv4Network, IPv4Address

from scapy.all import send, sniff, Packet
from scapy.contrib import gtp
from scapy.layers.inet import IP, UDP

UE_PORT = 400
PDN_PORT = 80
GPDU_PORT = 2152

GTPU_EXT_PSC_TYPE_UL = 1
GTPU_EXT_PSC_TYPE_DL = 0  # Unused

pkt_count = 0

ue_addresses_expected = set()  # UE addresses that we expect to receive packets for/from
downlink_teids_expected = set()  # Downlink TEIDs that we expect to receive packets for


def addrs_from_prefix(prefix: IPv4Network, count: int):
    addr_gen = iter(prefix)
    next(addr_gen)  # discard the address with all 0 host bits
    result = []
    for _ in range(count):
        result.append(str(next(addr_gen)))
    return result


def send_gtp(args: argparse.Namespace):
    pkts = []
    # Uplink TEIDs each differ by 2, since each UE is assigned two TEIDs: one for up, one for down
    for (teid, ue_addr) in zip(range(args.teid_base, args.teid_base + args.flow_count * 2, 2),
                               addrs_from_prefix(args.ue_pool, args.flow_count)):
        pkt = IP(src=str(args.enb_addr), dst=str(args.n3_addr)) / \
              UDP(dport=GPDU_PORT) / \
              gtp.GTP_U_Header(gtp_type=0xff, teid=teid)
        if args.qfi is not None:
            pkt = pkt / gtp.GTPPDUSessionContainer(type=GTPU_EXT_PSC_TYPE_UL, QFI=args.qfi)
        pkt = pkt / IP(src=ue_addr, dst=str(args.pdn_addr)) / \
              UDP(sport=UE_PORT, dport=args.pdn_port) / \
              ' '.join(['P4 is inevitable!'] * 50)
        pkts.append(pkt)

    print("Sending %d packets, %d times each" % (len(pkts), args.count))
    send(pkts, inter=1.0 / (args.rate * args.flow_count), loop=args.count != 1, count=args.count,
         verbose=True)


def send_udp(args: argparse.Namespace):
    pkts = []
    for ue_addr in addrs_from_prefix(args.ue_pool, args.flow_count):
        pkt = IP(src=args.pdn_addr, dst=ue_addr) / \
              UDP(sport=args.pdn_port, dport=UE_PORT) / \
              ' '.join(['P4 is great!'] * 50)
        pkts.append(pkt)

    print("Sending %d packets, %d times each" % (len(pkts), args.count))
    send(pkts, inter=1.0 / (args.rate * args.flow_count), loop=args.count != 1, count=args.count,
         verbose=True)


def handle_pkt(pkt: Packet, kind: str, exit_on_success: bool, qfi: int):
    exp_gtp_encap = False
    if kind == "gtp":
        exp_gtp_encap = True
    elif kind != "udp":
        print("Bad handle_pkt kind argument: %s" % kind)
        exit(1)

    if qfi is not None:
        exp_psc = True
    else:
        exp_psc = False

    global pkt_count
    pkt_count = pkt_count + 1
    if gtp.GTP_U_Header in pkt:
        is_gtp_encap = True
    else:
        is_gtp_encap = False

    if gtp.GTPPDUSessionContainer in pkt:
        has_psc = True
    else:
        has_psc = False

    print("[%d] %d bytes: %s -> %s, is_gtp_encap=%s, has_psc=%s\n\t%s" \
          % (pkt_count, len(pkt), pkt[IP].src, pkt[IP].dst,
             is_gtp_encap, has_psc, pkt.summary()))

    if exit_on_success:
        # If not encapped, the UE address is the outer source
        ue_addr = IPv4Address(pkt[IP].src)
        # If encapped, the UE address is the inner dest
        print("Outer src %s, dst %s" % (pkt[IP].src, pkt[IP].dst))
        if is_gtp_encap:
            print("Inner src %s, dst %s" %
                  (pkt[gtp.GTP_U_Header][IP].src, pkt[gtp.GTP_U_Header][IP].dst))
            ue_addr = IPv4Address(pkt[gtp.GTP_U_Header][IP].dst)
            teid = pkt[gtp.GTP_U_Header].teid

        if exp_gtp_encap == is_gtp_encap:
            print("Received an expected packet with UE address %s!" % str(ue_addr))
            ue_addresses_expected.discard(ue_addr)
            if is_gtp_encap:
                print("With TEID %s!" % str(teid))
                downlink_teids_expected.discard(teid)
        else:
            if exp_gtp_encap:
                print("Expected a GTP encapped packet but received non-encapped!")
            else:
                print("Expected a non-GTP-encapped packet but received encapped!")
            exit(1)
        if exp_psc:
            if has_psc:
                print("Received expected packet with PSC QFI: %s" %
                      pkt[gtp.GTPPDUSessionContainer].QFI)
                if pkt[gtp.GTPPDUSessionContainer].QFI != qfi:
                    exit(1)
            else:
                print("Expected a GTP with PSC but received packet without PSC!")

        if len(ue_addresses_expected) == 0 and len(downlink_teids_expected) == 0:
            print("Received packets for/from all UEs!")
            exit(0)


def handle_gtp_end_marker(pkt: Packet, exit_on_success: bool):
    global pkt_count
    pkt_count = pkt_count + 1

    print("[%d] %d bytes: %s -> %s\n\t%s" \
          % (pkt_count, len(pkt), pkt[IP].src, pkt[IP].dst, pkt.summary()))

    if exit_on_success:
        # gtp_type=254 --> end_marker
        if gtp.GTP_U_Header in pkt and pkt[gtp.GTP_U_Header].gtp_type == 254:
            print("Received GTP End Marker packet! teid=%s" % pkt[gtp.GTP_U_Header].teid)
            exit(0)


def sniff_gtp(args: argparse.Namespace):
    sniff_stuff(args, kind="gtp")


def sniff_udp(args: argparse.Namespace):
    sniff_stuff(args, kind="udp")


def sniff_gtp_end_marker(args: argparse.Namespace):
    sniff_stuff(args, kind="gtp-end-marker")


def sniff_nothing(args: argparse.Namespace):

    def succeed_on_timeout(signum, frame):
        print("Received no packet after %d seconds, as expected." % args.timeout)
        exit(0)

    def fail_on_sniff(pkt):
        print("Received unexpected packet!")
        pkt.show()
        exit(1)

    signal.signal(signal.SIGALRM, succeed_on_timeout)
    signal.alarm(args.timeout)
    sniff(count=0, store=False, filter="udp", prn=fail_on_sniff)


def sniff_stuff(args: argparse.Namespace, kind: str):
    exit_on_success = False
    if args.timeout != 0:
        exit_on_success = True
        # wait timeout seconds or exit
        signal.signal(signal.SIGALRM, handle_timeout)
        signal.alarm(args.timeout)

    # Add UE addresses for which we expect to receive packets to the global set
    for ue_addr in addrs_from_prefix(args.ue_pool, args.flow_count):
        ue_addresses_expected.add(IPv4Address(ue_addr))
    # Add downlink TEIDs for which we expect to receive packets to the global set
    if kind == 'gtp' and not args.no_verify_teid:
        for teid in range(args.teid_base, args.teid_base + args.flow_count * 2, 2):
            # We assign 2 TEIDs to a UE and the second one is for downlink
            downlink_teids_expected.add(teid + 1)

    print("Will print a line for each UDP packet received...")

    if kind == 'udp' or kind == 'gtp':
        print("Expecting packets for/from the following UEs:", list(ue_addresses_expected))
        if kind == 'gtp' and not args.no_verify_teid:
            print("With downlink TEIDs:", list(downlink_teids_expected))
        if args.qfi is not None:
            print("With the following QFI: ", args.qfi)
        prn = lambda x: handle_pkt(x, kind, exit_on_success, args.qfi)
    elif kind == 'gtp-end-marker':
        prn = lambda x: handle_gtp_end_marker(x, exit_on_success)
    else:
        print("Bad sniff_stuff kind argument: %s" % kind)
        exit(1)
    sniff(count=0, store=False, filter="udp", prn=prn)


def handle_timeout(signum, frame):
    if len(ue_addresses_expected) != 0:
        print("Timeout! Did not receive expected packets for the following addresses:",
              ue_addresses_expected)
    if len(downlink_teids_expected) != 0:
        print("Timeout! Did not receive expected packets for the following TEID:",
              downlink_teids_expected)
    exit(1)


def main():
    funcs = {
        "send-gtp": send_gtp,
        "send-udp": send_udp,
        "recv-gtp": sniff_gtp,
        "recv-gtp-end-marker": sniff_gtp_end_marker,
        "recv-udp": sniff_udp,
        "recv-none": sniff_nothing
    }

    parser = argparse.ArgumentParser(formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("command", type=str, help="The action to perform", choices=funcs.keys())
    parser.add_argument("-t", dest="timeout", type=int, default=0,
                        help="How long to wait for packets before giving up")
    parser.add_argument("-c", dest="count", type=int, default=1,
                        help="How many packets to transmit for each flow")
    parser.add_argument("--rate", type=int, default=2,
                        help="Packets per second to send for each flow")
    parser.add_argument("--flow-count", type=int, default=1, help="How many flows to send/receive.")
    parser.add_argument("--ue-pool", type=IPv4Network, default=IPv4Network("17.0.0.0/24"),
                        help="The IPv4 prefix from which UE addresses will be drawn.")
    parser.add_argument("--n3-addr", type=IPv4Address, default=IPv4Address("140.0.0.1"),
                        help="The IPv4 address of the UPF's N3 interface")
    parser.add_argument("--enb-addr", type=IPv4Address, default=IPv4Address("140.0.100.1"),
                        help="The IPv4 address of the eNodeB")
    parser.add_argument("--pdn-addr", type=IPv4Address, default=IPv4Address("140.0.200.1"),
                        help="The IPv4 address of the PDN")
    parser.add_argument("--pdn-port", type=int, default=PDN_PORT, help="The L4 port of the PDR")
    parser.add_argument(
        "--teid-base", type=int, default=255, help="The first TEID to use for the first UE flow. " +
        "Further TEIDs will be generated by incrementing.")
    parser.add_argument("--qfi", type=int, default=None, help="The QoS Flow Identifier")
    parser.add_argument("--no-verify-teid", action='store_true',
                        help="If this argument is present, don't verify the downlink TEIDs")
    args = parser.parse_args()

    funcs[args.command](args)


if __name__ == "__main__":
    main()
