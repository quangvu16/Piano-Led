"""Wi-Fi pixel output, one LED at a time, with four standard protocols:

* WLED realtime ("wled:IP"): UDP 21324, DNRGB
* Art-Net ("artnet:IP[/universe]", e.g. BanlanX SP801E): ArtDmx on UDP 6454, 170 RGB LEDs per universe
* E1.31 / sACN ("e131:IP[/universe]", universe 1 = first 170 LEDs): UDP 5568 – WLED, ESPixelStick, Falcon,
  most Wi-Fi pixel controllers, and the LED Piano Strip firmware
* DDP ("ddp:IP"): UDP 4048, 480 LEDs per packet – WLED, ESPixelStick, xLights controllers

Also: Adalight (Arduino Uno/Nano on USB or HC-05 Bluetooth, see adalight_packet) and discovery of
LED Piano Strip ESP32 boards on the Wi-Fi ("strip:IP", TCP 7777, see discover_strips) and of WLED
controllers (Bonjour / mDNS, see find_controllers).

WLED over Wi-Fi: the computer/phone drives a WS2812B strip through a ready-made WLED controller
(e.g. Gledopto "WLED" boxes; nothing to program).

WLED realtime UDP (kno.wled.ge/interfaces/udp-realtime): port 21324, DNRGB packets
``04, timeout, startHigh, startLow, r, g, b, …`` (≤ 489 LEDs per packet).
Accepts the same ws2812 messages as the ESP32 strip, keeps the whole strip picture here and sends
all of it (re-sent every second); if the app goes away WLED returns to its own mode after 3 s.
Same as WledTransport.kt (Android) and WledLink.swift (iPhone/Mac).
"""
from __future__ import annotations

import socket
import threading

from . import ws2812 as WS

PREFIX = "wled:"
ARTNET_PREFIX = "artnet:"
E131_PREFIX = "e131:"
DDP_PREFIX = "ddp:"
STRIP_PREFIX = "strip:"     # LED Piano Strip ESP32 over Wi-Fi (TCP, same A5 messages both ways)
ADA_PREFIX = "ada:"         # Adalight: "ada:serial:/dev/cu.usbmodem1101", "ada:serial:COM5"
E131_PORT = 5568
DDP_PORT = 4048
DDP_PER_PACKET = 480        # 1440 bytes
STRIP_PORT = 7777
DISCOVERY_PORT = 7778
E131_CID = bytes.fromhex("4c4544506961e00a8c5e5a1e0d2c7701")  # fixed 16-byte sender id ("LEDPia…")
ARTNET_PORT = 6454
ARTNET_PER_UNIVERSE = 170
PORT = 21324
PER_PACKET = 489
TIMEOUT_S = 3


def parse_address(address: str) -> tuple[str, int, bool, int]:
    """→ (host, port, is Art-Net, start universe)."""
    art = address.startswith(ARTNET_PREFIX)
    hp = address[len(ARTNET_PREFIX if art else PREFIX):] if address.startswith((PREFIX, ARTNET_PREFIX)) else address
    hp = hp.strip()
    uni = 0
    if art and "/" in hp:
        hp, u = hp.split("/", 1)
        uni = max(0, min(32767, int(u or 0)))
    if hp.count(":") == 1:
        host, port = hp.split(":")
        return host, int(port), art, uni
    return hp, ARTNET_PORT if art else PORT, art, uni


def parse_target(address: str) -> tuple[str, str, int, int]:
    """→ (kind "wled" | "artnet" | "e131" | "ddp", host, port, start universe)."""
    for kind, prefix, port, uni in (("artnet", ARTNET_PREFIX, ARTNET_PORT, 0), ("e131", E131_PREFIX, E131_PORT, 1),
                                    ("ddp", DDP_PREFIX, DDP_PORT, 0), ("wled", PREFIX, PORT, 0)):
        if address.startswith(prefix):
            hp = address[len(prefix):].strip()
            break
    else:
        kind, hp, port, uni = "wled", address.strip(), PORT, 0
    if "/" in hp:
        hp, u = hp.split("/", 1)
        uni = max(1 if kind == "e131" else 0, min(63999, int(u or uni)))
    if hp.count(":") == 1:
        hp, p = hp.split(":")
        port = int(p)
    return kind, hp, port, uni


def e131_packets(frame: bytes, brightness: int = 255, start_universe: int = 1, sequence: int = 1,
                 source: str = "LED Piano") -> list[bytes]:
    """E1.31 (sACN) data packets: root layer, framing layer, DMP layer; 170 RGB LEDs (510 slots) per universe."""
    leds = len(frame) // 3
    out, start, u = [], 0, max(1, start_universe)
    name = source.encode()[:63].ljust(64, b"\x00")
    while start < leds or not out:
        count = min(ARTNET_PER_UNIVERSE, leds - start)
        slots = bytes(v * brightness // 255 for v in frame[start * 3:(start + count) * 3])
        n = 126 + len(slots)
        pkt = bytearray()
        pkt += bytes([0x00, 0x10, 0x00, 0x00]) + b"ASC-E1.17\x00\x00\x00"
        pkt += (0x7000 | (n - 16)).to_bytes(2, "big") + (4).to_bytes(4, "big") + E131_CID
        pkt += (0x7000 | (n - 38)).to_bytes(2, "big") + (2).to_bytes(4, "big") + name
        pkt += bytes([100, 0, 0, sequence & 0xFF, 0]) + u.to_bytes(2, "big")
        pkt += (0x7000 | (n - 115)).to_bytes(2, "big") + bytes([0x02, 0xA1, 0, 0, 0, 1])
        pkt += (1 + len(slots)).to_bytes(2, "big") + b"\x00" + slots
        out.append(bytes(pkt))
        start += max(count, 1)
        u += 1
    return out


def ddp_packets(frame: bytes, brightness: int = 255, sequence: int = 1) -> list[bytes]:
    """DDP packets: flags (V1, PUSH on the last), sequence 1..15, type RGB24, id 1, offset, length."""
    data = bytes(v * brightness // 255 for v in frame)
    chunk = DDP_PER_PACKET * 3
    out = []
    for off in range(0, max(len(data), 1), chunk):
        body = data[off:off + chunk]
        last = off + chunk >= len(data)
        out.append(bytes([0x41 if last else 0x40, (sequence & 0x0F) or 1, 0x0B, 0x01]) + off.to_bytes(4, "big")
                   + len(body).to_bytes(2, "big") + body)
    return out


def adalight_packet(frame: bytes, brightness: int = 255) -> bytes:
    """Adalight (Arduino Uno/Nano sketches, Hyperion, Prismatik): "Ada", count−1 (2 bytes), checksum, r g b …"""
    n = max(1, len(frame) // 3) - 1
    hi, lo = (n >> 8) & 0xFF, n & 0xFF
    return b"Ada" + bytes([hi, lo, hi ^ lo ^ 0x55]) + bytes(v * brightness // 255 for v in frame[:(n + 1) * 3])


def discover_strips(timeout: float = 1.5) -> list[tuple[str, str]]:
    """LED Piano Strip ESP32 boards on this Wi-Fi: broadcast "LEDPIANO?" on UDP 7778 → [(name, "strip:IP")]."""
    found: dict[str, str] = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.3)
        import time
        end = time.monotonic() + timeout
        next_probe = 0.0
        while time.monotonic() < end:
            if time.monotonic() >= next_probe:
                try:
                    s.sendto(b"LEDPIANO?", ("255.255.255.255", DISCOVERY_PORT))
                except OSError:
                    pass
                next_probe = time.monotonic() + 0.5
            try:
                data, (ip, _) = s.recvfrom(256)
            except OSError:
                continue
            if data.startswith(b"LEDPIANO "):
                found[ip] = data[9:].decode(errors="replace").strip() or "LED Piano Strip"
    finally:
        s.close()
    return [(f"{name} (Wi-Fi)", STRIP_PREFIX + ip) for ip, name in found.items()]


MDNS_GROUP = ("224.0.0.251", 5353)
WLED_SERVICE = "_wled._tcp.local"
STRIP_SERVICE = "_ledpiano._tcp.local"


def mdns_query(*services: str, qid: int = 0) -> bytes:
    """One mDNS question per service (type PTR, class IN). Sent from an ordinary port, it is a "legacy
    unicast" query (RFC 6762 §6.7), so devices answer straight back to us — no port 5353 needed."""
    out = bytes([qid >> 8, qid & 0xFF, 0, 0, 0, len(services), 0, 0, 0, 0, 0, 0])
    for svc in services:
        for label in svc.split("."):
            b = label.encode()
            out += bytes([len(b)]) + b
        out += b"\x00\x00\x0c\x00\x01"  # end of name, PTR, IN
    return out


def _dns_name(msg: bytes, pos: int) -> tuple[str, int]:
    """A (possibly compressed) DNS name at pos → (name, position after it)."""
    labels, end, jumps = [], None, 0
    while pos < len(msg):
        n = msg[pos]
        if n == 0:
            pos += 1
            break
        if n & 0xC0 == 0xC0:  # pointer
            if end is None:
                end = pos + 2
            pos = ((n & 0x3F) << 8) | msg[pos + 1]
            jumps += 1
            if jumps > 20:
                break
            continue
        labels.append(msg[pos + 1:pos + 1 + n].decode(errors="replace"))
        pos += 1 + n
    return ".".join(labels), end if end is not None else pos


def mdns_answers(msg: bytes) -> list[tuple[str, int, bytes, int]]:
    """All records of an mDNS reply: (name, type, rdata, rdata offset)."""
    if len(msg) < 12:
        return []
    qd, an, ns, ar = (int.from_bytes(msg[i:i + 2], "big") for i in (4, 6, 8, 10))
    pos = 12
    try:
        for _ in range(qd):
            _, pos = _dns_name(msg, pos)
            pos += 4
        out = []
        for _ in range(an + ns + ar):
            name, pos = _dns_name(msg, pos)
            rtype = int.from_bytes(msg[pos:pos + 2], "big")
            rdlen = int.from_bytes(msg[pos + 8:pos + 10], "big")
            pos += 10
            out.append((name, rtype, msg[pos:pos + rdlen], pos))
            pos += rdlen
        return out
    except (IndexError, ValueError):
        return []


def parse_mdns_reply(msg: bytes, sender_ip: str) -> list[tuple[str, str]]:
    """PTR answers for our two services → [(name shown, address)]. The IP is the A record of the
    service's host, or else the sender (the controller answers for itself)."""
    recs = mdns_answers(msg)
    a_records = {name.lower(): ".".join(str(b) for b in data) for name, t, data, _ in recs if t == 1 and len(data) == 4}
    srv_host = {}
    for name, t, _, off in recs:
        if t == 33:  # SRV: priority, weight, port, target
            srv_host[name.lower()] = _dns_name(msg, off + 6)[0].lower()
    out = []
    for name, t, _, off in recs:
        if t != 12:
            continue
        svc = name.lower().rstrip(".")
        if svc not in (WLED_SERVICE, STRIP_SERVICE):
            continue
        instance = _dns_name(msg, off)[0]
        ip = a_records.get(srv_host.get(instance.lower(), ""), sender_ip)
        label = instance[:-(len(svc) + 1)] if instance.lower().endswith("." + svc) else instance
        if svc == WLED_SERVICE:
            out.append((f"WLED · {label or 'WLED'} (Wi-Fi)", PREFIX + ip))
        else:
            out.append((f"{label or 'LED Piano Strip'} (Wi-Fi)", STRIP_PREFIX + ip))
    return out


def find_mdns(timeout: float = 2.0) -> list[tuple[str, str]]:
    """WLED controllers and LED Piano Strip boards that announce themselves with Bonjour / mDNS."""
    import time
    found: dict[str, str] = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        s.settimeout(0.3)
        query = mdns_query(WLED_SERVICE, STRIP_SERVICE, qid=0x4C50)
        end = time.monotonic() + timeout
        next_probe = 0.0
        while time.monotonic() < end:
            if time.monotonic() >= next_probe:
                try:
                    s.sendto(query, MDNS_GROUP)
                except OSError:
                    pass
                next_probe = time.monotonic() + 0.7
            try:
                data, (ip, _) = s.recvfrom(4096)
            except OSError:
                continue
            for name, addr in parse_mdns_reply(data, ip):
                found.setdefault(addr, name)
    finally:
        s.close()
    return [(name, addr) for addr, name in found.items()]


def find_controllers(timeout: float = 2.0) -> list[tuple[str, str]]:
    """Everything the apps can drive over Wi-Fi without typing an IP: LED Piano Strip boards (broadcast +
    mDNS) and WLED controllers (mDNS). Same as WifiFinder.kt (Android) and LedFinder.swift (iPhone/Mac)."""
    import threading
    found: dict[str, str] = {}
    strips: list[tuple[str, str]] = []
    t = threading.Thread(target=lambda: strips.extend(discover_strips(min(timeout, 1.5))), daemon=True)
    t.start()
    for name, addr in find_mdns(timeout):
        found.setdefault(addr, name)
    t.join(timeout)
    for name, addr in strips:
        found[addr] = name  # the broadcast reply has the board's exact name
    return sorted(((n, a) for a, n in found.items()), key=lambda d: (not d[1].startswith(STRIP_PREFIX), d[0]))


def artdmx_packets(frame: bytes, brightness: int = 255, start_universe: int = 0, sequence: int = 1) -> list[bytes]:
    """ArtDmx packets for the whole strip: "Art-Net\\0", OpCode 0x5000 (little-endian), protocol 14,
    sequence, physical 0, universe (low byte, then net), length (big-endian, even), data."""
    leds = len(frame) // 3
    out, start, u = [], 0, start_universe
    while start < leds:
        count = min(ARTNET_PER_UNIVERSE, leds - start)
        body = bytes(v * brightness // 255 for v in frame[start * 3:(start + count) * 3])
        if len(body) % 2:
            body += b"\x00"
        out.append(b"Art-Net\x00" + bytes([0x00, 0x50, 0, 14, sequence & 0xFF, 0, u & 0xFF, (u >> 8) & 0x7F,
                                             len(body) >> 8, len(body) & 0xFF]) + body)
        start += count
        u += 1
    return out


def dnrgb_packets(frame: bytes, brightness: int = 255, timeout: int = TIMEOUT_S) -> list[bytes]:
    """The whole strip picture (r, g, b per LED) as WLED DNRGB packets."""
    leds = len(frame) // 3
    out, start = [], 0
    while start < leds:
        count = min(PER_PACKET, leds - start)
        body = bytes(v * brightness // 255 for v in frame[start * 3:(start + count) * 3])
        out.append(bytes([4, timeout, (start >> 8) & 0xFF, start & 0xFF]) + body)
        start += count
    return out


class MessageParser:
    """Reassembles ``A5 op len payload chk`` messages (same checks as the ESP32 firmware)."""

    def __init__(self):
        self.st, self.op, self.len, self.chk = 0, 0, 0, 0
        self.data = bytearray()

    def feed(self, b: int) -> bool:
        if self.st == 0:
            if b == 0xA5:
                self.st = 1
        elif self.st == 1:
            self.op, self.chk, self.st = b, b, 2
        elif self.st == 2:
            self.len, self.chk, self.data = b, self.chk ^ b, bytearray()
            self.st = 4 if b == 0 else 3
        elif self.st == 3:
            self.data.append(b)
            self.chk ^= b
            if len(self.data) == self.len:
                self.st = 4
        else:
            self.st = 0
            return b == self.chk
        return False


class WledLink:
    """Keeps the whole strip picture and sends it with one of the Wi-Fi protocols (or Adalight bytes)."""

    def __init__(self, address: str, num_leds, write=None):
        """``write``: for Adalight, the function that writes bytes to the serial port."""
        self.write = write
        if write is not None:
            self.kind, self.host, self.port, self.universe = "adalight", "", 0, 0
        else:
            self.kind, self.host, self.port, self.universe = parse_target(address)
        self.artnet = self.kind == "artnet"
        self.seq = 0
        if write is None:
            socket.getaddrinfo(self.host, self.port)  # raises when the name/IP is wrong
        self.num_leds = num_leds  # callable → current LED count
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM) if write is None else None
        self.frame = bytearray(3 * max(1, num_leds()))
        self.brightness = 255
        self.parser = MessageParser()
        self.lock = threading.Lock()
        self.alive = True
        threading.Thread(target=self._keepalive, daemon=True).start()

    def send(self, data: bytes):
        with self.lock:
            changed = False
            for b in data:
                if self.parser.feed(b):
                    self._apply()
                    changed = True
            if changed:
                self._flush()

    def close(self):
        with self.lock:
            self.alive = False
            self.frame = bytearray(len(self.frame))
            self._flush(timeout=1)  # dark, and WLED goes back to its own mode after 1 s
        if self.sock is not None:
            self.sock.close()

    def _apply(self):
        p = self.parser
        n = max(1, min(1500, int(self.num_leds())))
        if len(self.frame) != n * 3:
            self.frame = bytearray(n * 3)
        if p.op == WS.OP_PIXELS:
            self.frame = bytearray(n * 3)
            for i in range(0, p.len - 4, 5):
                idx = p.data[i] | p.data[i + 1] << 8
                if idx < n:
                    self.frame[idx * 3:idx * 3 + 3] = p.data[i + 2:i + 5]
        elif p.op == WS.OP_FILL:
            self.frame = bytearray(bytes(p.data[:3]) * n)
        elif p.op == WS.OP_BRIGHTNESS:
            self.brightness = p.data[0]

    def _flush(self, timeout=TIMEOUT_S):
        try:
            self.seq = 1 if self.seq >= 255 else self.seq + 1
            f = bytes(self.frame)
            if self.kind == "adalight":
                self.write(adalight_packet(f, self.brightness))
                return
            if self.kind == "artnet":
                pkts = artdmx_packets(f, self.brightness, self.universe, self.seq)
            elif self.kind == "e131":
                pkts = e131_packets(f, self.brightness, self.universe, self.seq)
            elif self.kind == "ddp":
                pkts = ddp_packets(f, self.brightness, self.seq)
            else:
                pkts = dnrgb_packets(f, self.brightness, timeout)
            for pkt in pkts:
                self.sock.sendto(pkt, (self.host, self.port))
        except Exception:
            pass

    def _keepalive(self):
        import time
        while True:
            time.sleep(1)
            with self.lock:
                if not self.alive:
                    return
                self._flush()
