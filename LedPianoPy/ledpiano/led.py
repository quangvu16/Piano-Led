"""LED strip over Bluetooth LE (bleak), same protocols as the Android app:
SP611E / BanlanX (16-band spectrum frames), the legacy Nordic UART per-key
controller (7F [key,state]… 6E), and the ESP32 + WS2812B "LED Piano Strip"
where the app sets every LED itself (see ws2812.py) – over Bluetooth or a USB
cable. Runs its own asyncio loop in a thread."""
from __future__ import annotations

import asyncio
import math
import threading
import time

from . import learning as LRN
from . import wled as WL
from . import ws2812 as WS
from .midi import LEFT, PIANO_HIGHEST, PIANO_LOWEST, MIDDLE_C

try:
    from bleak import BleakClient, BleakScanner
except Exception:  # pragma: no cover
    BleakClient = BleakScanner = None

SP_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
SP_WRITE = "0000ffe1-0000-1000-8000-00805f9b34fb"
NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"


def sp_frame(op: int, *data: int) -> bytes:
    assert len(data) <= 17
    return bytes([0xA0, op, len(data), *[d & 0xFF for d in data]])


class Sp611e:
    power = staticmethod(lambda on: sp_frame(0x62, 1 if on else 0))
    effect = staticmethod(lambda i: sp_frame(0x63, i))
    brightness = staticmethod(lambda v: sp_frame(0x66, max(0, min(255, v))))
    length = staticmethod(lambda n: sp_frame(0x68, max(1, min(150, n))))
    rgb = staticmethod(lambda r, g, b, lv=255: sp_frame(0x69, r, g, b, lv))
    sensitivity = staticmethod(lambda v: sp_frame(0x6B, v))
    audio_input = staticmethod(lambda v: sp_frame(0x6C, v))
    feed = staticmethod(lambda bands: sp_frame(0x6D, *bands))
    EFFECT_SOLID, EFFECT_SOUND_SINGLE, EFFECT_SOUND_RGB = 0xBE, 0xCA, 0xC9


def nus_key_byte(pitch: int, organ_offset: int) -> int:
    return 88 - (organ_offset - 21) - (pitch - 21)


def nus_state(hand: str, black: bool) -> int:
    if hand == LEFT:
        return 0x04 if black else 0x03
    return 0x02 if black else 0x01


CHORD_WINDOW = 0.04  # notes starting within 40 ms count as one chord


def one_per_hand(active: dict) -> list:
    """Pick the ONE note per hand that gets an LED.

    active maps pitch -> (hand, black, start_time). Each hand shows its newest
    note; in a chord the right hand shows the top note (melody) and the left
    hand the bottom note (bass). Unknown hand counts as right hand (Led Organ
    does the same). Returns [(pitch, hand, black)] sorted by pitch."""
    groups: dict[bool, list] = {}
    for p, (h, b, t) in active.items():
        groups.setdefault(h == LEFT, []).append((p, h, b, t))
    out = []
    for is_left, notes in groups.items():
        newest = max(n[3] for n in notes)
        chord = [n for n in notes if newest - n[3] <= CHORD_WINDOW]
        pick = min(chord) if is_left else max(chord)
        out.append(pick[:3])
    return sorted(out)


def nus_frame(entries: list[tuple[int, int]]) -> bytes:
    if not entries:
        return bytes([0x7F, 0x01, 0x00, 0x6E])
    out = [0x7F]
    for k, s in entries:
        out += [k & 0xFF, s & 0xFF]
    return bytes(out + [0x6E])


class ZoneMapper:
    def __init__(self, zones: int = 16):
        self.zones = zones
        self.levels = [0.0] * zones
        self.lock = threading.Lock()

    def zone(self, pitch):
        return max(0, min(self.zones - 1, (pitch - PIANO_LOWEST) * self.zones // 88))

    def note_on(self, pitch, vel):
        z, t = self.zone(pitch), 0.35 + 0.65 * min(max(vel, 1), 127) / 127
        with self.lock:
            for i, k in ((z, 1), (z - 1, 0.5), (z + 1, 0.5)):
                if 0 <= i < self.zones:
                    self.levels[i] = max(self.levels[i], t * k)

    def tick(self, dt):
        f = math.exp(-dt / 0.18)
        with self.lock:
            self.levels = [l * f for l in self.levels]

    def has_energy(self):
        return any(l > 0.01 for l in self.levels)

    def bands16(self):
        n = self.zones
        out = []
        with self.lock:
            for b in range(16):
                z0, z1 = b * n // 16, max(b * n // 16 + 1, (b + 1) * n // 16)
                out.append(int(max(self.levels[z0:z1]) * 255))
        return out

    def reset(self):
        with self.lock:
            self.levels = [0.0] * self.zones


class LedController:
    DISCONNECTED, CONNECTING, CONNECTED = "disconnected", "connecting", "connected"

    def __init__(self, settings):
        self.s = settings
        self.state = self.DISCONNECTED
        self.device_name: str | None = None
        self.listeners: list = []
        self.mapper = ZoneMapper(16)
        self.active: dict[int, tuple[str, bool, float]] = {}
        self._guidance = 1.0
        self.playing = False
        self.manual = False
        self._client = None
        self._addr = None
        self._user_disc = False
        self._retries = 0
        # Learning features (pixel strips): look-ahead preview, mistake flashes, loudness.
        self.vel: dict[int, int] = {}
        self.preview: list[tuple[int, str]] = []
        self.flashes: dict[int, tuple[int, float]] = {}
        self._last_by_hand: dict[str, tuple[int, int]] = {}
        self._expect: frozenset = frozenset()
        # ESP32 with a Bluetooth MIDI piano: its notes come back here.
        self.piano_listeners: list = []      # callbacks(pitch, velocity); velocity 0 = released
        self.esp32_status = (0, None)        # (flags, ip)
        self.esp32_song_max = WS.DEFAULT_SONG_NOTES  # notes per song the board holds (STATUS)
        self._rx = WL.MessageParser()
        self._loop = asyncio.new_event_loop()
        threading.Thread(target=self._loop.run_forever, daemon=True).start()
        threading.Thread(target=self._ticker, daemon=True).start()

    @property
    def available(self) -> bool:
        return BleakClient is not None

    @property
    def per_key(self) -> bool:
        """One exact key can be lit (Led Organ box or WS2812B strip)."""
        return self.s["led_transport"] in ("legacy_nus",) + ("ws2812", "wled", "sp801e", "e131", "ddp", "adalight")

    @property
    def is_ws(self) -> bool:
        """ESP32 + WS2812B: the app sets each LED itself."""
        return self.s["led_transport"] in ("ws2812", "wled", "sp801e", "e131", "ddp", "adalight")

    @property
    def whole_strip(self) -> bool:
        """BanlanX SP630E / SP802E: the whole strip shows the colour of the hand that plays."""
        return self.s["led_transport"] in ("sp630e", "sp802e")

    def _notify(self, msg=None):
        for cb in list(self.listeners):
            cb(self.state, self.device_name, msg)

    def _submit(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self._loop)

    # ---- connection ----
    def scan(self, seconds: float, done):
        """done(list[(name, address)]) is called from the BLE thread."""
        async def run():
            if BleakScanner is None:
                done([])
                return
            try:
                devs = await BleakScanner.discover(timeout=seconds)
                done([(d.name, d.address) for d in devs if d.name])
            except Exception:
                done([])
        self._submit(run())

    def connect(self, address: str, name: str | None):
        self._user_disc, self._retries = False, 0
        self._start(address, name)

    def _start(self, address, name):
        self._addr, self.device_name = address, name or address
        self.state = self.CONNECTING
        self._notify()
        self._submit(self._connect())

    async def _connect(self):
        if BleakClient is None:
            self.state = self.DISCONNECTED
            self._notify("Thiếu thư viện bleak")
            return
        try:
            await self._disconnect_client()
            if self._addr.startswith("serial:"):
                await self._connect_serial(self._addr[len("serial:"):])
                return
            if self._addr.startswith(WL.STRIP_PREFIX):
                await self._connect_tcp(self._addr[len(WL.STRIP_PREFIX):])
                return
            if self._addr.startswith(WL.ADA_PREFIX + "serial:"):
                await self._connect_adalight(self._addr[len(WL.ADA_PREFIX + "serial:"):])
                return
            if self._addr.startswith((WL.PREFIX, WL.ARTNET_PREFIX, WL.E131_PREFIX, WL.DDP_PREFIX)):
                self._wled = WL.WledLink(self._addr, lambda: WS.config(self.s)["num_leds"])
                keep = self._addr.startswith(WL.ARTNET_PREFIX) and self.s["led_transport"] == "sp801e"
                kind = self._wled.kind
                self.s.data["led_transport"] = "sp801e" if keep else kind if kind in ("e131", "ddp") else "wled"
                self.state = self.CONNECTED
                self._retries = 0
                self.s.data["led_address"], self.s.data["led_name"] = self._addr, self.device_name
                self.s.save()
                await self._write(WS.brightness(self.s["led_brightness"]))
                await self._write(WS.OFF)
                self._notify()
                return
            if self.s["led_transport"] in ("wled", "sp801e", "e131", "ddp", "adalight"):
                self.s.data["led_transport"] = "legacy_nus"  # Bluetooth device: auto-detected below
            c = BleakClient(self._addr, disconnected_callback=lambda _c: self._on_lost())
            await c.connect()
            # Use whichever protocol the controller really speaks (LED Piano Strip = WS2812B
            # pixels, Led Organ box = per-key Nordic UART, BanlanX = SP611E), whatever Settings says.
            uuids = {str(ch.uuid).lower() for sv in c.services for ch in sv.characteristics}
            if WS.WS_WRITE in uuids:
                self.s.data["led_transport"] = "ws2812"
            elif NUS_WRITE in uuids:
                self.s.data["led_transport"] = "legacy_nus"
            elif SP_WRITE in uuids:
                # BanlanX SP611E / SP630E / SP802E share this service: the name (or Settings) decides.
                nm = (self.device_name or "").upper()
                if nm.startswith("SP630E"):
                    self.s.data["led_transport"] = "sp630e"
                elif nm.startswith("SP802E"):
                    self.s.data["led_transport"] = "sp802e"
                elif nm.startswith("SP611E") or not self.whole_strip:
                    self.s.data["led_transport"] = "sp611e"
            self._client = c
            self.state = self.CONNECTED
            self._retries = 0
            self.s.data["led_address"], self.s.data["led_name"] = self._addr, self.device_name
            self.s.save()
            if self.is_ws:
                await self._write(WS.brightness(self.s["led_brightness"]))
                await self._write(WS.OFF)
                await self._write(self._config_msg())
                try:  # firmware 2: piano notes and status from the ESP32
                    await c.start_notify(WS.WS_NOTIFY, lambda _h, data: self._on_rx(bytes(data)))
                except Exception:
                    pass
            elif self.whole_strip:
                self._strip_rgb = 0
                for f in (Sp630e.power(True), Sp630e.static_mode(), Sp630e.brightness(self.s["led_brightness"]),
                          Sp630e.color(0, self.s["led_brightness"])):
                    await self._write(f)
            elif not self.per_key:
                for f in (Sp611e.power(True), Sp611e.effect(self._effect()),
                          Sp611e.brightness(int(self.s["led_brightness"] * self._guidance)),
                          Sp611e.length(self.s["led_length"]), Sp611e.audio_input(1),
                          Sp611e.sensitivity(16), Sp611e.rgb(255, 255, 255, 255)):
                    await self._write(f)
            else:
                await self._write(nus_frame([]))
            self._notify()
        except Exception as e:
            self._client = None
            self.state = self.DISCONNECTED
            self._notify(f"Kết nối thất bại: {e}")
            self._schedule_retry()

    async def _connect_serial(self, port):
        """ESP32 on a USB cable: the same WS2812B messages at 115200 baud."""
        import serial  # pyserial
        def open_port():
            # DTR and RTS off before opening: on ESP32 dev boards they drive EN (reset) and GPIO0 (BOOT,
            # also the firmware's practice button) through the auto-reset transistors.
            ser = serial.Serial()
            ser.port, ser.baudrate, ser.timeout = port, WS.BAUD, 0
            ser.dtr = ser.rts = False
            ser.open()
            return ser
        ser = await asyncio.get_running_loop().run_in_executor(None, open_port)
        await asyncio.sleep(1.5)  # opening the port resets most ESP32 boards; wait for the firmware to start
        self._serial = ser
        self._client = None
        self.s.data["led_transport"] = "ws2812"
        self.state = self.CONNECTED
        self._retries = 0
        self.s.data["led_address"], self.s.data["led_name"] = self._addr, self.device_name
        self.s.save()
        await self._write(WS.brightness(self.s["led_brightness"]))
        await self._write(WS.OFF)
        await self._write(self._config_msg())
        threading.Thread(target=self._serial_reader, args=(ser,), daemon=True).start()
        self._notify()

    async def _connect_tcp(self, host):
        """LED Piano Strip ESP32 over Wi-Fi: TCP 7777, the same messages as Bluetooth/USB, both ways."""
        def open_sock():
            import socket
            h, _, p = host.partition(":")
            sock = socket.create_connection((h, int(p or WL.STRIP_PORT)), timeout=4)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(0.05)
            return SocketStream(sock)
        ser = await asyncio.get_running_loop().run_in_executor(None, open_sock)
        self._serial = ser
        self._client = None
        self.s.data["led_transport"] = "ws2812"
        self.state = self.CONNECTED
        self._retries = 0
        self.s.data["led_address"], self.s.data["led_name"] = self._addr, self.device_name
        self.s.save()
        await self._write(WS.brightness(self.s["led_brightness"]))
        await self._write(WS.OFF)
        await self._write(self._config_msg())
        threading.Thread(target=self._serial_reader, args=(ser,), daemon=True).start()
        self._notify()

    async def _connect_adalight(self, port):
        """Arduino Uno/Nano (or ESP) running an Adalight sketch, on USB or a Bluetooth HC-05 serial port.
        Plain Arduinos have no Bluetooth LE, so this is how the classic FastLED tutorial board joins in."""
        import serial  # pyserial
        def open_port():
            ser = serial.Serial()
            ser.port, ser.baudrate, ser.timeout, ser.write_timeout = port, WS.BAUD, 0, 1
            ser.dtr = ser.rts = False  # an Uno resets when DTR switches on
            ser.open()
            return ser
        ser = await asyncio.get_running_loop().run_in_executor(None, open_port)
        await asyncio.sleep(2.0)  # in case the board restarted anyway (Uno bootloader ≈ 1.5 s)
        self._serial_port = ser
        self._client = None
        self._wled = WL.WledLink("", lambda: WS.config(self.s)["num_leds"], write=ser.write)
        self.s.data["led_transport"] = "adalight"
        self.state = self.CONNECTED
        self._retries = 0
        self.s.data["led_address"], self.s.data["led_name"] = self._addr, self.device_name
        self.s.save()
        await self._write(WS.brightness(self.s["led_brightness"]))
        await self._write(WS.OFF)
        self._notify()

    def _serial_reader(self, ser):
        while getattr(self, "_serial", None) is ser:
            try:
                data = ser.read(64)
            except Exception:  # cable pulled / Wi-Fi link closed
                if getattr(self, "_serial", None) is ser:
                    self._serial = None
                    self._on_lost()
                return
            if data:
                self._on_rx(data)
            else:
                time.sleep(0.01)

    def _on_rx(self, data: bytes):
        """Messages from the ESP32: piano notes (OP_NOTE) and status (OP_STATUS)."""
        for b in data:
            if not self._rx.feed(b):
                continue
            op, d = self._rx.op, self._rx.data
            if op == WS.OP_NOTE and len(d) >= 2:
                for cb in list(self.piano_listeners):
                    cb(d[0], d[1])
            elif op == WS.OP_STATUS and len(d) >= 5:
                ip = ".".join(str(x) for x in d[1:5]) if any(d[1:5]) else None
                if len(d) >= 9:
                    self.esp32_song_max = (d[7] | d[8] << 8) or WS.DEFAULT_SONG_NOTES
                if (d[0], ip) != self.esp32_status:
                    self.esp32_status = (d[0], ip)
                    self._notify()

    def _config_msg(self) -> bytes:
        return WS.config_message(WS.config(self.s), WS.rgb_hex(self.s["color_right"]), WS.rgb_hex(self.s["color_left"]))

    def send_config(self):
        """Tell the ESP32 the strip geometry and colours (after Settings change)."""
        if self.state == self.CONNECTED and self.s["led_transport"] == "ws2812":
            self.send(self._config_msg())

    def send_song(self, song) -> int:
        """Store a song on the ESP32 so it can be practised without the phone. Returns notes sent."""
        if self.state != self.CONNECTED or self.s["led_transport"] != "ws2812":
            return 0
        notes = [(n.start, n.pitch, n.hand) for n in song.by_time]
        cap = self.esp32_song_max
        for m in WS.song_messages(notes, getattr(song, "title", None), cap):
            self.send(m)
        return min(len(notes), cap)

    def _effect(self):
        return {"single": Sp611e.EFFECT_SOLID, "full": Sp611e.EFFECT_SOUND_RGB}.get(
            self.s.data.get("led_effect", "spectrum"), Sp611e.EFFECT_SOUND_SINGLE)

    def _on_lost(self):
        if self.state == self.CONNECTED:
            self._client = None
            self.state = self.DISCONNECTED
            self._notify("Mất kết nối với thiết bị")
            self._schedule_retry()

    def _schedule_retry(self):
        if self._user_disc or not self.s["auto_reconnect"] or self._retries >= 3 or not self._addr:
            return
        self._retries += 1

        def later():
            time.sleep(3)
            if self.state == self.DISCONNECTED and not self._user_disc:
                self._start(self._addr, self.device_name)
        threading.Thread(target=later, daemon=True).start()

    def auto_connect(self):
        addr = self.s["led_address"]
        if addr and self.s["auto_reconnect"] and self.state == self.DISCONNECTED:
            self.connect(addr, self.s["led_name"])

    def disconnect(self):
        self._user_disc = True
        self._submit(self._disconnect_client())
        self.state = self.DISCONNECTED
        self._notify()

    async def _disconnect_client(self):
        w, self._wled = getattr(self, "_wled", None), None
        if w is not None:
            w.close()
        sp, self._serial_port = getattr(self, "_serial_port", None), None
        if sp is not None:
            try:
                sp.close()
            except Exception:
                pass
        ser, self._serial = getattr(self, "_serial", None), None
        if ser is not None:
            try:
                ser.close()
            except Exception:
                pass
        c, self._client = self._client, None
        if c is not None:
            try:
                await c.disconnect()
            except Exception:
                pass

    async def _write(self, data: bytes):
        w = getattr(self, "_wled", None)
        if w is not None:
            w.send(data)
            return
        ser = getattr(self, "_serial", None)
        if ser is not None:
            try:
                ser.write(data)
            except Exception:
                self._serial = None
                self._on_lost()
            return
        c = self._client
        if c is None:
            return
        uuid = WS.WS_WRITE if self.is_ws else NUS_WRITE if self.per_key else SP_WRITE
        try:
            for i in range(0, len(data), 20):
                await c.write_gatt_char(uuid, data[i:i + 20], response=False)
        except Exception:
            pass

    def send(self, data: bytes):
        if self.state == self.CONNECTED:
            self._submit(self._write(data))

    # ---- keys ----
    @property
    def guidance(self):
        return self._guidance

    @guidance.setter
    def guidance(self, g):
        g = max(0.0, min(1.0, g))
        if g == self._guidance:
            return
        self._guidance = g
        self._apply_guidance()

    def _apply_guidance(self):
        if self.is_ws:
            self.send(WS.brightness(self.s["led_brightness"]))
            self._last_frame = None
            self._flush()
        elif self.per_key:
            self._flush()
        elif self.whole_strip:
            self._strip_rgb = None
            self._flush()
        else:
            lv = int(self.s["led_brightness"] * (0.15 + 0.85 * self._guidance))
            self.send(Sp611e.brightness(0 if self._guidance <= 0 else lv))

    def set_brightness(self, v):
        self.s["led_brightness"] = v
        self._apply_guidance()

    def set_length(self, n):
        self.s["led_length"] = n
        if not self.per_key and not self.whole_strip:
            self.send(Sp611e.length(n))

    def key_on(self, pitch, hand, vel=100):
        if self.manual:
            return
        # Only one LED per hand: see one_per_hand().
        self.vel[pitch] = vel
        self.active[pitch] = (hand, pitch % 12 in (1, 3, 6, 8, 10), time.monotonic())
        shown = any(p == pitch for p, _, _ in one_per_hand(self.active))
        if self._guidance > 0 and shown:
            self.mapper.note_on(pitch, vel)
        if self.per_key or self.whole_strip:
            self._flush()

    def key_off(self, pitch):
        if self.manual:
            return
        if self.active.pop(pitch, None) is None:
            return
        if self.per_key or self.whole_strip:
            self._flush()

    def all_off(self):
        self.mapper.reset()
        self.active.clear()
        self.preview = []
        self.flashes.clear()
        self._last_by_hand.clear()
        self._last_frame = None
        if self.is_ws:
            self.send(WS.OFF)
            if self.s["led_transport"] == "ws2812" and self._expect:
                self._expect = frozenset()
                self.send(WS.expect_message([]))
        elif self.per_key:
            self.send(nus_frame([]))
        elif self.whole_strip:
            self._flush()

    # ---- learning features on pixel strips ----
    def set_preview(self, items):
        """Next note of each hand, shown dimly before it is due: [(pitch, hand)]."""
        items = list(items)
        if items != self.preview:
            self.preview = items
            if self.is_ws and not self.manual:
                self._flush()

    def flash(self, pitch, rgb, ms=250):
        """Short coloured flash on one key: red = wrong key, green = on time, orange = early/late."""
        if not self.is_ws or self.manual or not self.s["led_feedback"]:
            return
        self.flashes[pitch] = (rgb, time.monotonic() + ms / 1000)
        self._flush()
        t = threading.Timer(ms / 1000 + 0.02, self._expire)
        t.daemon = True
        t.start()

    def _expire(self):
        now = time.monotonic()
        for p in [p for p, (_, until) in self.flashes.items() if until <= now]:
            del self.flashes[p]
        self._flush()

    def _ws_pixels(self, shown, skip=()):
        px: dict[int, int] = {}
        if self.s["led_preview"] and self._guidance >= 0.5:
            for p, h in self.preview:
                q = self.ws_pixel(p, h)
                if q:
                    px[q[0]] = WS.scale(q[1], 0.18)
        for p, h, _ in shown:
            q = self.ws_pixel(p, h)
            if q and q[0] not in skip:
                rgb = q[1]
                if self.s["led_dynamics"]:
                    rgb = WS.scale(rgb, LRN.dyn_scale(self.vel.get(p, 100)))
                px[q[0]] = rgb
        now = time.monotonic()
        for p, (rgb, until) in list(self.flashes.items()):
            if until > now:
                q = WS.led_for_pitch(p, WS.config(self.s))
                if q >= 0:
                    px[q] = rgb
        return sorted(px.items())

    def ws_pixel(self, pitch, hand):
        """(LED, 0xRRGGBB) for one key on the WS2812B strip, or None when it has no LED."""
        led = WS.led_for_pitch(pitch, WS.config(self.s))
        if led < 0:
            return None
        base = WS.rgb_hex(self.s["color_left"] if hand == LEFT else self.s["color_right"])
        return led, WS.black_key_color(base) if WS.is_black(pitch) else base

    def _flush(self):
        shown = [] if self._guidance < 0.5 else one_per_hand(self.active)
        if self.whole_strip:
            # One colour for the whole strip: the hand of the newest key, dark when nothing plays.
            newest = max(shown, key=lambda x: self.active.get(x[0], (None, None, 0))[2], default=None)
            rgb = 0 if newest is None else WS.rgb_hex(self.s["color_left"] if newest[1] == LEFT else self.s["color_right"])
            if rgb != getattr(self, "_strip_rgb", None):
                self._strip_rgb = rgb
                self.send(Sp630e.color(rgb, self.s["led_brightness"]))
            return
        if self.is_ws:
            # Same LED for a new note of the same hand (sparse strips): blink it so a new key is noticed.
            blink = set()
            for p, h, _ in shown:
                q = self.ws_pixel(p, h)
                last = self._last_by_hand.get(h)
                if q and last and last[0] != p and last[1] == q[0]:
                    blink.add(q[0])
                if q:
                    self._last_by_hand[h] = (p, q[0])
            if blink:
                self.send(WS.pixels(self._ws_pixels(shown, skip=blink)))
                self._last_frame = None
                t = threading.Timer(0.07, self._flush)
                t.daemon = True
                t.start()
                return
            frame = WS.pixels(self._ws_pixels(shown))
            if frame != getattr(self, "_last_frame", None):
                self._last_frame = frame
                self.send(frame)
            if self.s["led_transport"] == "ws2812":
                exp = frozenset(self.active)
                if exp != self._expect:
                    self._expect = exp
                    self.send(WS.expect_message(exp))
            return
        off = self.s["organ_offset"]
        frame = nus_frame([(nus_key_byte(p, off), nus_state(h, b)) for p, h, b in shown])
        if frame == getattr(self, "_last_frame", None):
            return
        self._last_frame = frame
        self.send(frame)

    def show_keys(self, pitches):
        self.manual = True
        if self.is_ws:
            self.send(WS.pixels([px for px in (self.ws_pixel(p, "R") for p in pitches) if px]))
        elif self.per_key:
            off = self.s["organ_offset"]
            self.send(nus_frame([(nus_key_byte(p, off), 0x01) for p in sorted(pitches)]))
        else:
            for p in pitches:
                self.mapper.note_on(p, 127)

    def end_manual(self):
        self.manual = False
        self.all_off()

    def test_lights(self):
        if self.state != self.CONNECTED:
            return

        def run_ws():
            # Exactly the HowToMechatronics FastLED example (LEDs 0, 1, 2, 5, 9, 14, 19 one by one,
            # 500 ms apart, in its colours), then a light runs over every key.
            self.manual = True
            try:
                for n in range(1, len(WS.TUTORIAL) + 1):
                    if self.state != self.CONNECTED:
                        return
                    self.send(WS.pixels(WS.TUTORIAL[:n]))
                    time.sleep(0.5)
                time.sleep(0.5)
                c = WS.config(self.s)
                for p in range(c["lowest"], c["lowest"] + c["keys"]):
                    if self.state != self.CONNECTED:
                        return
                    px = self.ws_pixel(p, "R")
                    if px:
                        self.send(WS.pixels([px]))
                    time.sleep(0.03)
            finally:
                self.manual = False
                self.all_off()

        if self.is_ws:
            threading.Thread(target=run_ws, daemon=True).start()
            return

        def run_strip():
            # Whole-strip controller: right-hand colour, left-hand colour, then off.
            self.manual = True
            try:
                for c in (self.s["color_right"], self.s["color_left"]):
                    self.send(Sp630e.color(WS.rgb_hex(c), self.s["led_brightness"]))
                    time.sleep(0.7)
            finally:
                self.manual = False
                self._strip_rgb = None
                self.all_off()

        if self.whole_strip:
            threading.Thread(target=run_strip, daemon=True).start()
            return

        def run():
            self.manual = True
            try:
                for p in range(PIANO_LOWEST, PIANO_HIGHEST + 1, 2):
                    if self.per_key:
                        self.send(nus_frame([(nus_key_byte(p, self.s["organ_offset"]), 0x01)]))
                    else:
                        self.mapper.note_on(p, 127)
                    time.sleep(0.035)
                time.sleep(0.2)
            finally:
                self.manual = False
                self.all_off()
        threading.Thread(target=run, daemon=True).start()

    def _ticker(self):
        last = time.perf_counter()
        while True:
            now = time.perf_counter()
            self.mapper.tick(now - last)
            last = now
            if (self.state == self.CONNECTED and not self.per_key and not self.whole_strip
                    and (self.playing or self.mapper.has_energy())):
                self.send(Sp611e.feed(self.mapper.bands16()))
            time.sleep(0.04)


class SocketStream:
    """A TCP socket that looks like a pyserial port (read / write / close) for the ESP32 Wi-Fi link."""

    def __init__(self, sock):
        self.sock = sock

    def read(self, n: int) -> bytes:
        import socket
        try:
            data = self.sock.recv(n)
        except socket.timeout:
            return b""
        if not data:
            raise ConnectionError("closed")
        return data

    def write(self, data: bytes):
        self.sock.sendall(data)

    def close(self):
        self.sock.close()


class Sp630e:
    """BanlanX SP630E (and, untested, SP802E) over Bluetooth: service FFE0 (or E0FF), write FFE1.

    No per-LED command: the whole strip shows one colour. Protocol from UniLED
    (github.com/monty68/uniled, lib/ble/banlanx_6xx.py, MIT): 53 cmd 00 01 00 len payload…
    """

    @staticmethod
    def _msg(cmd: int, *payload: int) -> bytes:
        return bytes([0x53, cmd, 0x00, 0x01, 0x00, len(payload)] + [b & 0xFF for b in payload])

    @staticmethod
    def power(on: bool) -> bytes:
        return Sp630e._msg(0x50, 1 if on else 0)

    @staticmethod
    def brightness(level: int) -> bytes:
        return Sp630e._msg(0x51, 0x00, max(0, min(255, int(level))))

    @staticmethod
    def static_mode() -> bytes:
        """Static colour mode, solid effect (once after connecting)."""
        return Sp630e._msg(0x53, 0x01, 0x01)

    @staticmethod
    def color(rgb: int, level: int) -> bytes:
        return Sp630e._msg(0x52, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, max(0, min(255, int(level))))


__all__ = ["LedController", "Sp611e", "Sp630e", "nus_frame", "nus_key_byte", "MIDDLE_C"]
