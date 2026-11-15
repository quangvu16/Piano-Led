"""Each key lights exactly ONE LED and every other LED is off – for all 88 keys, both hands,
through all three LED-by-LED outputs (ESP32 Bluetooth/USB message, WLED packets, Art-Net packets)."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from ledpiano import wled as WL  # noqa: E402
from ledpiano import ws2812 as WS  # noqa: E402
from ledpiano.led import LedController  # noqa: E402
from ledpiano.practice import SETTINGS_DEFAULT  # noqa: E402


class Store:
    def __init__(self, **kw):
        self.data = dict(SETTINGS_DEFAULT, led_transport="ws2812", **kw)
    def __getitem__(self, k): return self.data[k]
    def __setitem__(self, k, v): self.data[k] = v
    def save(self): pass


def strip_from_message(msg: bytes, n: int) -> bytearray:
    """What the strip shows after one PIXELS message (the firmware/WLED/Art-Net all start from black)."""
    p = WL.MessageParser()
    frame = bytearray(n * 3)
    for b in msg:
        if p.feed(b) and p.op == WS.OP_PIXELS:
            frame = bytearray(n * 3)
            for i in range(0, p.len - 4, 5):
                idx = p.data[i] | p.data[i + 1] << 8
                frame[idx * 3:idx * 3 + 3] = p.data[i + 2:i + 5]
    return frame


def last_pixels(sent):
    return [m for m in sent if m[1] == WS.OP_PIXELS][-1]


def lit(frame) -> list[int]:
    return [i for i in range(len(frame) // 3) if any(frame[i * 3:i * 3 + 3])]


def check(**kw):
    led = LedController(Store(**kw))
    sent = []
    led.state = led.CONNECTED
    led.send = sent.append
    c = WS.config(led.s)
    leds_used = set()
    for pitch in range(c["lowest"], c["lowest"] + c["keys"]):
        for hand in ("R", "L"):
            led.all_off()
            led.key_on(pitch, hand)
            frame = strip_from_message(last_pixels(sent), c["num_leds"])
            on = lit(frame)
            assert on == [WS.led_for_pitch(pitch, c)], (pitch, hand, on)   # one LED, the key's own
            # Same picture through WLED and Art-Net packets.
            w = b"".join(pk[4:] for pk in WL.dnrgb_packets(bytes(frame)))
            a = b"".join(pk[18:18 + min(170, c["num_leds"] - u * 170) * 3]
                         for u, pk in enumerate(WL.artdmx_packets(bytes(frame))))
            assert lit(w) == on and lit(a) == on
            led.key_off(pitch)
            assert lit(strip_from_message(last_pixels(sent), c["num_leds"])) == []        # released: all off
        leds_used.add(on[0])
    return c, len(leds_used)


def test_88_keys_144_per_metre():
    c, used = check()
    assert used == 88            # every key its own LED


def test_88_keys_96_per_metre():
    c, used = check(ws_density=96)
    assert used == 88


def test_61_keys_reversed():
    c, used = check(ws_keys=61, ws_reversed=True)
    assert used == 61


def test_30_per_metre_one_led_each_but_shared():
    """30 LEDs/m: an LED every 33 mm, keys are 23.5 mm wide, so neighbouring keys share LEDs.
    Each key still lights exactly ONE LED (its nearest) and everything else stays off."""
    c, used = check(ws_density=30)
    assert c["num_leds"] == 37 and used == 37   # every LED serves 1–3 keys
    # the chosen LED is the nearest one to the key's centre
    for p in range(21, 109):
        centre = WS.key_centre_mm(p, 21) / (1000 / 30)
        assert abs(WS.led_for_pitch(p, c) - centre) <= 0.5
