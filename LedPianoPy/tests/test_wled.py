"""WLED over Wi-Fi: the app's LED picture arrives as WLED DNRGB packets (fake WLED on localhost)."""
import os
import socket
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from ledpiano import wled as WL  # noqa: E402
from ledpiano import ws2812 as WS  # noqa: E402


def test_dnrgb_packets_split_and_brightness():
    frame = bytes([200, 100, 0]) * 600
    pk = WL.dnrgb_packets(frame, brightness=128)
    assert [p[0] for p in pk] == [4, 4] and pk[0][1] == WL.TIMEOUT_S
    assert (pk[0][2] << 8 | pk[0][3]) == 0 and (pk[1][2] << 8 | pk[1][3]) == 489
    assert len(pk[0]) == 4 + 489 * 3 and len(pk[1]) == 4 + 111 * 3
    assert pk[0][4:7] == bytes([100, 50, 0])


def test_controller_drives_fake_wled():
    from ledpiano.led import LedController
    from ledpiano.practice import SETTINGS_DEFAULT

    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(2)
    port = rx.getsockname()[1]

    class Store:
        def __init__(self):
            self.data = dict(SETTINGS_DEFAULT)
        def __getitem__(self, k): return self.data[k]
        def __setitem__(self, k, v): self.data[k] = v
        def save(self): pass

    led = LedController(Store())
    led.connect(f"wled:127.0.0.1:{port}", "WLED test")
    deadline = time.time() + 3
    while led.state != led.CONNECTED and time.time() < deadline:
        time.sleep(0.02)
    assert led.state == led.CONNECTED and led.s["led_transport"] == "wled" and led.is_ws

    def latest():
        """Drain and return the newest full picture (r, g, b per LED)."""
        pkt = rx.recv(4096)
        rx.settimeout(0.15)
        try:
            while True:
                pkt = rx.recv(4096)
        except socket.timeout:
            pass
        rx.settimeout(2)
        assert pkt[0] == 4 and pkt[2] == 0 and pkt[3] == 0
        return pkt[4:]

    led.key_on(60, "R")
    led.key_on(48, "L")
    time.sleep(0.3)
    px = latest()
    c = WS.config(led.s)
    lit = [i for i in range(len(px) // 3) if px[i * 3:i * 3 + 3] != b"\x00\x00\x00"]
    assert lit == sorted([WS.led_for_pitch(60, c), WS.led_for_pitch(48, c)])  # exactly 2 LEDs, one per hand
    assert len(px) // 3 == c["num_leds"]

    led.all_off()
    time.sleep(0.3)
    assert set(latest()) == {0}
    led.disconnect()
    time.sleep(0.3)
    rx.close()


def test_artdmx_packet_layout():
    frame = bytes(range(1, 1 + 3 * 200))[:600] if False else bytes([10, 20, 30]) * 200
    pk = WL.artdmx_packets(frame, 255, start_universe=1, sequence=7)
    assert len(pk) == 2                                   # 170 + 30 LEDs
    p = pk[0]
    assert p[:8] == b"Art-Net\x00" and p[8:10] == b"\x00\x50" and p[10:12] == b"\x00\x0e"
    assert p[12] == 7 and p[14] == 1 and p[15] == 0       # sequence, universe 1
    assert (p[16] << 8 | p[17]) == 510 and len(p) == 18 + 510
    assert pk[1][14] == 2 and (pk[1][16] << 8 | pk[1][17]) == 90
    # odd channel count is padded to even
    assert (WL.artdmx_packets(bytes(3), 255)[0][17]) == 4


def test_controller_drives_fake_artnet_node():
    from ledpiano.led import LedController
    from ledpiano.practice import SETTINGS_DEFAULT

    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(2)
    port = rx.getsockname()[1]

    class Store:
        def __init__(self):
            self.data = dict(SETTINGS_DEFAULT)
        def __getitem__(self, k): return self.data[k]
        def __setitem__(self, k, v): self.data[k] = v
        def save(self): pass

    led = LedController(Store())
    led.connect(f"artnet:127.0.0.1:{port}/1", "Art-Net test")
    deadline = time.time() + 3
    while led.state != led.CONNECTED and time.time() < deadline:
        time.sleep(0.02)
    assert led.state == led.CONNECTED and led.is_ws
    led.key_on(60, "R")
    time.sleep(0.3)
    # collect the newest packet of each universe
    got = {}
    rx.settimeout(0.3)
    try:
        while True:
            p = rx.recv(2048)
            got[p[14] | p[15] << 8] = p
    except socket.timeout:
        pass
    assert set(got) == {1, 2}  # 177 LEDs → universes 1 and 2
    c = WS.config(led.s)
    lit = []
    for uni, p in got.items():
        data = p[18:]
        for i in range(len(data) // 3):
            if data[i * 3:i * 3 + 3] != b"\x00\x00\x00":
                lit.append((uni - 1) * 170 + i)
    assert lit == [WS.led_for_pitch(60, c)]
    led.disconnect()
    time.sleep(0.3)
    rx.close()


def test_sp630e_messages():
    """BanlanX SP630E / SP802E (UniLED banlanx_6xx): 53 cmd 00 01 00 len payload."""
    from ledpiano.led import Sp630e
    assert Sp630e.power(True) == bytes([0x53, 0x50, 0, 1, 0, 1, 1])
    assert Sp630e.static_mode() == bytes([0x53, 0x53, 0, 1, 0, 2, 1, 1])
    assert Sp630e.color(0xF97316, 200) == bytes([0x53, 0x52, 0, 1, 0, 4, 0xF9, 0x73, 0x16, 200])
    assert Sp630e.brightness(300) == bytes([0x53, 0x51, 0, 1, 0, 2, 0, 255])


def test_whole_strip_follows_hand():
    from ledpiano import led as L
    from ledpiano.practice import SETTINGS_DEFAULT

    class Store:
        def __init__(self):
            self.data = dict(SETTINGS_DEFAULT, led_transport="sp630e")
        def __getitem__(self, k): return self.data[k]
        def __setitem__(self, k, v): self.data[k] = v
        def save(self): pass

    s = Store()
    c = L.LedController(s)
    sent = []
    c.state = c.CONNECTED
    c.send = sent.append
    right = L.Sp630e.color(L.WS.rgb_hex(s["color_right"]), s["led_brightness"])
    left = L.Sp630e.color(L.WS.rgb_hex(s["color_left"]), s["led_brightness"])
    c.key_on(60, "R")
    assert sent[-1] == right
    c.key_on(48, "L")          # newest key is the left hand's
    assert sent[-1] == left
    c.key_off(48)
    assert sent[-1] == right
    c.key_off(60)
    assert sent[-1] == L.Sp630e.color(0, s["led_brightness"])
    assert not any(m[:1] == b"\xa5" for m in sent)  # no pixel messages to a BanlanX box
