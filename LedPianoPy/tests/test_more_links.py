"""E1.31 / DDP / Adalight packets and the ESP32 Wi-Fi (TCP) link."""
import asyncio
import socket
import threading
import time

from ledpiano import wled as WL
from ledpiano import ws2812 as WS


def test_parse_target():
    assert WL.parse_target("e131:192.168.1.5") == ("e131", "192.168.1.5", 5568, 1)
    assert WL.parse_target("e131:192.168.1.5/3") == ("e131", "192.168.1.5", 5568, 3)
    assert WL.parse_target("ddp:10.0.0.2") == ("ddp", "10.0.0.2", 4048, 0)
    assert WL.parse_target("artnet:1.2.3.4/2") == ("artnet", "1.2.3.4", 6454, 2)
    assert WL.parse_target("wled:1.2.3.4:9999") == ("wled", "1.2.3.4", 9999, 0)


def test_e131_layout():
    frame = bytes([10, 20, 30]) * 200  # 200 LEDs → 2 universes (170 + 30)
    p = WL.e131_packets(frame, 255, start_universe=1, sequence=9)
    assert len(p) == 2 and len(p[0]) == 126 + 510 and len(p[1]) == 126 + 90
    a = p[0]
    assert a[4:16] == b"ASC-E1.17\x00\x00\x00"
    assert int.from_bytes(a[16:18], "big") == 0x7000 | (len(a) - 16)
    assert int.from_bytes(a[18:22], "big") == 4 and int.from_bytes(a[40:44], "big") == 2
    assert a[108] == 100 and a[111] == 9
    assert int.from_bytes(a[113:115], "big") == 1 and int.from_bytes(p[1][113:115], "big") == 2
    assert a[117] == 0x02 and a[118] == 0xA1 and int.from_bytes(a[123:125], "big") == 511
    assert a[125] == 0 and a[126:129] == bytes([10, 20, 30])


def test_ddp_layout():
    p = WL.ddp_packets(bytes(range(256)) * 9, sequence=3)  # 768 LEDs → 2 packets
    assert [x[0] for x in p] == [0x40, 0x41]                # PUSH on the last one
    assert p[0][1] == 3 and p[0][2] == 0x0B and p[0][3] == 1
    assert int.from_bytes(p[1][4:8], "big") == 1440 and int.from_bytes(p[0][8:10], "big") == 1440


def test_adalight():
    pkt = WL.adalight_packet(bytes([255, 0, 0, 0, 0, 255]), brightness=255)
    assert pkt[:3] == b"Ada" and pkt[3:6] == bytes([0, 1, 0 ^ 1 ^ 0x55]) and pkt[6:] == bytes([255, 0, 0, 0, 0, 255])
    out = []
    link = WL.WledLink("", lambda: 4, write=out.append)
    link.send(WS.pixels([(2, 0x00FF00)]))
    assert out[-1] == b"Ada" + bytes([0, 3, 3 ^ 0x55]) + bytes(6) + bytes([0, 255, 0]) + bytes(3)
    link.close()
    assert out[-1][6:] == bytes(12)                           # dark when closed


def test_e131_link_sends_udp():
    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(2)
    port = rx.getsockname()[1]
    link = WL.WledLink(f"e131:127.0.0.1:{port}", lambda: 3)
    link.send(WS.pixels([(1, 0x112233)]))
    data, _ = rx.recvfrom(1000)
    assert data[4:13] == b"ASC-E1.17" and data[126:135] == bytes([0, 0, 0, 0x11, 0x22, 0x33, 0, 0, 0])
    link.close()
    rx.close()


def test_strip_over_wifi_tcp():
    """A fake ESP32: receives the app's messages and sends a piano note back."""
    from ledpiano.led import LedController
    from ledpiano.practice import SETTINGS_DEFAULT

    class Store:
        def __init__(self):
            self.data = dict(SETTINGS_DEFAULT, auto_reconnect=False)
        def __getitem__(self, k): return self.data[k]
        def __setitem__(self, k, v): self.data[k] = v
        def save(self): pass

    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]
    got = bytearray()

    def esp32():
        c, _ = srv.accept()
        c.settimeout(3)
        end = time.time() + 3
        while len(got) < 20 and time.time() < end:
            try:
                got.extend(c.recv(1024))
            except socket.timeout:
                break
        c.sendall(bytes([0xA5, WS.OP_NOTE, 2, 60, 90, WS.OP_NOTE ^ 2 ^ 60 ^ 90]))
        time.sleep(0.5)
        c.close()
    threading.Thread(target=esp32, daemon=True).start()

    led = LedController(Store())
    notes = []
    led.piano_listeners.append(lambda p, v: notes.append((p, v)))
    led.connect(f"strip:127.0.0.1:{port}", "LED Piano Strip (Wi-Fi)")
    end = time.time() + 5
    while not notes and time.time() < end:
        time.sleep(0.05)
    assert led.state == led.CONNECTED and led.s["led_transport"] == "ws2812"
    assert got[:4] == WS.brightness(led.s["led_brightness"])[:4]   # brightness, OFF, CONFIG arrived
    assert WS.OP_CONFIG in got
    assert notes == [(60, 90)]
    srv.close()


def test_song_title_and_s3_capacity():
    notes = [(i * 0.1, 60, "R") for i in range(5000)]
    msgs = WS.song_messages(notes, "Für Elise – Beethoven", max_notes=20000)
    assert msgs[0][1] == WS.OP_SONG_TITLE and msgs[0][3:-1].decode() == "Für Elise – Beethoven"
    assert msgs[1][1] == WS.OP_SONG_BEGIN and msgs[1][3] | msgs[1][4] << 8 == 5000   # all 5000 on an S3
    short = WS.song_messages(notes)                                                  # WROOM-32: 3000
    assert short[0][1] == WS.OP_SONG_BEGIN and short[0][3] | short[0][4] << 8 == 3000
    long_title = WS.song_messages(notes[:3], "ố" * 40)[0]                            # cut at 60 bytes, whole characters
    assert long_title[2] <= 60 and long_title[3:-1].decode()


def test_board_names():
    assert WS.board_name("LED Piano Strip") == "ESP32-WROOM-32"
    assert WS.board_name("LED Piano Strip S3") == "ESP32-S3-DevKitC N16R8"
    assert WS.board_name("LED Piano Strip C3 (Wi-Fi)") == "ESP32-C3 Super Mini"
    assert not WS.is_c3_name("LED Piano Strip S3")


def _mdns_reply(instance: str, service: str, host: str, ip: bytes) -> bytes:
    """A reply like WLED / ESPmDNS send: PTR answer (compressed), SRV + A as additional records."""
    def name(n):
        return b"".join(bytes([len(p)]) + p.encode() for p in n.split(".")) + b"\x00"
    msg = bytearray(b"\x00\x00\x84\x00\x00\x00\x00\x01\x00\x00\x00\x02")
    svc_off = 12 + 10 + 2 + 0  # filled below
    owner = name(service)
    msg += owner
    svc_off = 12
    inst = bytes([len(instance)]) + instance.encode() + bytes([0xC0, svc_off])
    msg += b"\x00\x0c\x00\x01\x00\x00\x11\x94" + len(inst).to_bytes(2, "big") + inst
    inst_off = len(msg) - len(inst)
    tgt = name(host)
    rd = b"\x00\x00\x00\x00\x00\x50" + tgt
    msg += bytes([0xC0, inst_off]) + b"\x00\x21\x80\x01\x00\x00\x00\x78" + len(rd).to_bytes(2, "big") + rd
    msg += name(host) + b"\x00\x01\x80\x01\x00\x00\x00\x78\x00\x04" + ip
    return bytes(msg)


def test_mdns_query_and_reply():
    q = WL.mdns_query(WL.WLED_SERVICE, WL.STRIP_SERVICE)
    assert q[5] == 2 and b"\x05_wled\x04_tcp\x05local\x00\x00\x0c\x00\x01" in q
    r = _mdns_reply("Kitchen", "_wled._tcp.local", "wled-ab12.local", bytes([192, 168, 1, 77]))
    assert WL.parse_mdns_reply(r, "10.0.0.9") == [("WLED · Kitchen (Wi-Fi)", "wled:192.168.1.77")]
    r = _mdns_reply("LED Piano Strip C3", "_ledpiano._tcp.local", "ledpiano.local", bytes([192, 168, 1, 60]))
    assert WL.parse_mdns_reply(r, "10.0.0.9") == [("LED Piano Strip C3 (Wi-Fi)", "strip:192.168.1.60")]
    assert WL.parse_mdns_reply(b"\x00" * 5, "1.2.3.4") == []
