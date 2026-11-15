"""WS2812B strip driven by the LED Piano Strip firmware (ESP32 + FastLED, folder LedPianoStrip).

The app decides which LED sits above each key and its colour, then sends the pixels, like
``leds[i] = CRGB(r, g, b); FastLED.show()`` in the HowToMechatronics FastLED example.
Message: ``A5 op len payload… checksum`` (checksum = op ^ len ^ every payload byte):

* 0x01 PIXELS: n × (indexLow, indexHigh, r, g, b) – all LEDs off, these on, show
* 0x02 BRIGHTNESS: 1 byte
* 0x03 FILL: r, g, b

Works over Bluetooth LE or the USB cable (115200 baud). Same logic as KeyMap.h (firmware),
Ws2812.kt (Android) and Ws2812.swift (iPhone/Mac).
"""
from __future__ import annotations

import math

WS_SERVICE = "8f1b0001-6c3e-4c1a-9f2e-5a1e0d2c7701"
WS_WRITE = "8f1b0002-6c3e-4c1a-9f2e-5a1e0d2c7701"
WS_NOTIFY = "8f1b0003-6c3e-4c1a-9f2e-5a1e0d2c7701"  # ESP32 → app: piano notes, status
OP_PIXELS, OP_BRIGHTNESS, OP_FILL = 0x01, 0x02, 0x03
# App → ESP32 (LedPianoStrip firmware 2):
OP_CONFIG = 0x04       # strip + piano geometry and hand colours (so the ESP32 can light keys itself)
OP_EXPECT = 0x05       # keys the learner should press now (ESP32 flashes green/red instantly)
OP_SONG_BEGIN = 0x06   # store a song on the ESP32 (practise without the phone): note count
OP_SONG_DATA = 0x07    # index (2 bytes) + notes × (start ms: 3 bytes, pitch, hand 0 = right / 1 = left)
OP_SONG_END = 0x08     # note count again = commit
OP_SONG_TITLE = 0x09   # song name, UTF-8 (≤ 60 bytes), sent just before BEGIN (firmware 2.1 song library)
# ESP32 → app (notifications on WS_NOTIFY, or the USB serial port):
OP_NOTE = 0x10         # pitch, velocity (0 = released) – from a Bluetooth MIDI piano paired with the ESP32
OP_STATUS = 0x11       # flags (bit0 piano, bit1 Wi-Fi, bit2 song stored, bit3 ESP32-S3, bit4 ESP32-C3), IPv4 (4 bytes),
                       # current song's notes (2), notes per song the board holds (2: 3000, or 20000 on an S3 N16R8)
DEFAULT_SONG_NOTES = 3000
SONG_PER_MSG = 49
MAX_PIXELS = 50
BAUD = 115200

# The HowToMechatronics FastLED example: these LEDs and colours, lit one by one 500 ms apart.
TUTORIAL = [(0, 0xFF0000), (1, 0x00FF00), (2, 0x0000FF), (5, 0x9600FF),
            (9, 0xFFC814), (14, 0x553CB4), (19, 0x32FF14)]

LOWEST_FOR_KEYS = {88: 21, 76: 28, 61: 36, 49: 36}


def is_black(p: int) -> bool:
    return p % 12 in (1, 3, 6, 8, 10)


def key_centre_mm(pitch: int, lowest: int, white_mm: float = 23.5) -> float:
    """Centre of a key in mm from the left edge of the lowest key (black keys sit on the gap)."""
    whites = sum(1 for p in range(lowest, pitch) if not is_black(p))
    return whites * white_mm if is_black(pitch) else (whites + 0.5) * white_mm


def leds_needed(keys: int, lowest: int, per_metre: float, white_mm: float = 23.5) -> int:
    whites = sum(1 for p in range(lowest, lowest + keys) if not is_black(p))
    return int(whites * white_mm * per_metre / 1000) + 1


def config(s) -> dict:
    """Strip + piano geometry from the settings store."""
    keys = int(s["ws_keys"]) if int(s["ws_keys"]) in LOWEST_FOR_KEYS else 88
    lowest = LOWEST_FOR_KEYS[keys]
    density = float(s["ws_density"])
    n = int(s["ws_num_leds"]) or leds_needed(keys, lowest, density)
    return {"num_leds": n, "per_metre": density, "lowest": lowest, "keys": keys, "white_mm": 23.5,
            "first": int(s["ws_first_led"]), "reversed": bool(s["ws_reversed"])}


def led_for_pitch(pitch: int, c: dict) -> int:
    """The LED above ``pitch``, or -1 when the key is not on this piano / off the strip."""
    if not c["lowest"] <= pitch < c["lowest"] + c["keys"]:
        return -1
    # Nearest LED to the key's centre (LED i sits i × spacing from the left edge of the lowest key).
    idx = c["first"] + math.floor(key_centre_mm(pitch, c["lowest"], c["white_mm"]) / (1000.0 / c["per_metre"]) + 0.5)
    if c["reversed"]:
        idx = c["num_leds"] - 1 - idx
    return idx if 0 <= idx < c["num_leds"] else -1


def _message(op: int, payload: bytes) -> bytes:
    chk = op ^ len(payload)
    for b in payload:
        chk ^= b
    return bytes([0xA5, op, len(payload)]) + payload + bytes([chk])


def config_message(c: dict, right: int, left: int) -> bytes:
    """Geometry + colours for the firmware's own key→LED map (same maths as led_for_pitch)."""
    first = c["first"] & 0xFFFF
    return _message(OP_CONFIG, bytes([
        c["num_leds"] & 0xFF, (c["num_leds"] >> 8) & 0xFF, int(c["per_metre"]) & 0xFF, c["lowest"] & 0x7F,
        c["keys"] & 0x7F, first & 0xFF, first >> 8, 1 if c["reversed"] else 0,
        (right >> 16) & 0xFF, (right >> 8) & 0xFF, right & 0xFF, (left >> 16) & 0xFF, (left >> 8) & 0xFF, left & 0xFF]))


def expect_message(pitches) -> bytes:
    return _message(OP_EXPECT, bytes(sorted({p & 0x7F for p in pitches})[:32]))


def song_messages(notes, title: str | None = None, max_notes: int = DEFAULT_SONG_NOTES) -> list[bytes]:
    """Store a song on the ESP32. notes: [(start seconds, pitch, hand "L"/"R")] in time order.
    max_notes: what the board holds (STATUS tells; 20000 on an ESP32-S3 N16R8)."""
    ev = [(int(round(t * 1000)) & 0xFFFFFF, p & 0x7F, 1 if h == "L" else 0) for t, p, h in notes][:max(1, min(65535, max_notes))]
    out = []
    if title:
        name = title.encode("utf-8")[:60]
        while name:  # do not cut a UTF-8 character in half
            try:
                name.decode("utf-8")
                break
            except UnicodeDecodeError:
                name = name[:-1]
        out.append(_message(OP_SONG_TITLE, name))
    out.append(_message(OP_SONG_BEGIN, bytes([len(ev) & 0xFF, len(ev) >> 8])))
    for i in range(0, len(ev), SONG_PER_MSG):
        body = bytearray([i & 0xFF, i >> 8])
        for ms, p, h in ev[i:i + SONG_PER_MSG]:
            body += bytes([ms & 0xFF, (ms >> 8) & 0xFF, ms >> 16, p, h])
        out.append(_message(OP_SONG_DATA, bytes(body)))
    out.append(_message(OP_SONG_END, bytes([len(ev) & 0xFF, len(ev) >> 8])))
    return out


def pixels(px: list[tuple[int, int]]) -> bytes:
    """px: [(led index, 0xRRGGBB)] – everything else off."""
    out = bytearray()
    for i, rgb in px[:MAX_PIXELS]:
        out += bytes([i & 0xFF, (i >> 8) & 0xFF, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF])
    return _message(OP_PIXELS, bytes(out))


def brightness(v: int) -> bytes:
    return _message(OP_BRIGHTNESS, bytes([max(0, min(255, int(v)))]))


def fill(rgb: int) -> bytes:
    return _message(OP_FILL, bytes([(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF]))


OFF = pixels([])


def scale(rgb: int, f: float) -> int:
    """Colour at a fraction of its brightness (look-ahead preview, dynamics)."""
    f = max(0.0, min(1.0, f))
    return (int(((rgb >> 16) & 0xFF) * f) << 16) | (int(((rgb >> 8) & 0xFF) * f) << 8) | int((rgb & 0xFF) * f)


def rgb_hex(h: str) -> int:
    try:
        return int(h.lstrip("#"), 16) & 0xFFFFFF
    except ValueError:
        return 0xFFFFFF


def black_key_color(rgb: int) -> int:
    """Dimmer version of the hand colour for black keys, so they stand out."""
    r, g, b = (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF
    return (r * 6 // 10) << 16 | (g * 6 // 10) << 8 | (b * 6 // 10)


def is_s3_name(name: str | None) -> bool:
    """The firmware advertises "LED Piano Strip S3" on an ESP32-S3 (e.g. ESP32-S3-DevKitC N16R8)."""
    import re
    return bool(name) and re.search(r"\bS3\b", name, re.I) is not None


def is_c3_name(name: str | None) -> bool:
    """The firmware advertises "LED Piano Strip C3" on an ESP32-C3 (e.g. ESP32-C3 Super Mini)."""
    import re
    return bool(name) and re.search(r"\bC3\b", name, re.I) is not None


def board_name(name: str | None) -> str:
    """The board behind a "LED Piano Strip…" name: "LED Piano Strip S3" → ESP32-S3-DevKitC N16R8 …"""
    if is_s3_name(name):
        return "ESP32-S3-DevKitC N16R8"
    if is_c3_name(name):
        return "ESP32-C3 Super Mini"
    return "ESP32-WROOM-32"


# USB chips on ESP32 boards → a readable name (vendor id, product id or None).
USB_CHIPS = {(0x303A, None): "ESP32-S3 / C3 (cổng USB)", (0x10C4, 0xEA60): "CP210x (ESP32-WROOM-32 / cổng COM của S3)",
             (0x1A86, 0x55D3): "CH343 (cổng COM của ESP32-S3)", (0x1A86, 0x55D4): "CH9102", (0x1A86, 0x7523): "CH340",
             (0x2341, None): "Arduino"}


def serial_ports(include_bluetooth: bool = False) -> list[tuple[str, str]]:
    """USB serial ports that could be an ESP32 / Arduino: [(label, "serial:<device>")].
    include_bluetooth: also paired Bluetooth serial modules (HC-05 / HC-06 show up as a port too)."""
    try:
        from serial.tools import list_ports
    except Exception:
        return []
    out = []
    for p in list_ports.comports():
        d = p.device.lower()
        bt = "bluetooth" in (p.description or "").lower() or any(k in d for k in ("hc-0", "rfcomm"))
        if bt and include_bluetooth:
            out.append((f"Bluetooth: {p.description or p.device}", "serial:" + p.device))
            continue
        if p.vid is None and "usb" not in d:
            continue
        chip = USB_CHIPS.get((p.vid, p.pid)) or USB_CHIPS.get((p.vid, None))
        out.append((f"USB · {chip}: {p.device}" if chip else f"USB: {p.description or p.device}", "serial:" + p.device))
    return out
