import os, time
import numpy as np
from ledpiano import midi as M
from ledpiano.engine import Engine, Listener
from ledpiano.practice import Coach, Lessons, PassResult, ScoreKeeper, HIT, WRONG
from ledpiano.inputs import yin_midi, RATE
from ledpiano.led import nus_frame, nus_key_byte, one_per_hand, Sp611e

A = os.path.join(os.path.dirname(__file__), "..", "ledpiano", "assets")

class Silent:
    def note_on(self, *a): pass
    def note_off(self, *a): pass
    def all_notes_off(self): pass
    def click(self, *a): pass

def song():
    s = M.load(os.path.join(A, "lessons", "l3_ode_to_joy.mid"), "ode"); M.assign_hands(s); return s

def test_load_and_hands():
    s = song()
    assert sum(n.hand == M.RIGHT for n in s.notes) == 30 and sum(n.hand == M.LEFT for n in s.notes) == 8
    b = M.BeatGrid(s); assert len(b.secs) == 32 and sum(b.down) == 8
    assert abs(b.beat_length_at(1) - 60 / 92) < 0.01

def test_all_assets_load():
    for d in ("lessons", "samples"):
        for f in os.listdir(os.path.join(A, d)):
            if f.endswith(".mid"):
                s = M.load(os.path.join(A, d, f)); assert s.duration > 0

def test_fingering():
    L = Lessons(A); l = L.find("asset://lessons/l3_ode_to_joy.mid"); s = song()
    f = Lessons.fingering(l, s); assert len(f) == 30
    assert L.next(l.key) is not None and len(L.all()) == 16

def test_score_and_coach():
    s = song(); rh = [n for n in s.notes if n.hand == M.RIGHT]; k = ScoreKeeper(); k.start_pass(rh)
    for i, n in enumerate(rh):
        if i != 5: assert k.on_key(n.pitch, n.start + 0.05, 100) == HIT
    assert k.on_key(30, 3.0, 100) == WRONG
    r = k.finish_pass(s.duration); assert (r.total, r.hits, r.wrong) == (30, 29, 1) and 40 <= r.mean_offset_ms <= 60
    c = Coach(); c.lights = Coach.FADE
    for _ in range(8): c.on_correct()
    assert c.guidance == 0.5; c.on_error(); c.on_error(); assert c.guidance == 1
    sp = 50
    for _ in range(3): sp = c.on_pass(PassResult(10, 10, 0, 0), sp, True) or sp
    assert sp == 60

def test_engine_gate_countin_loop():
    s = song(); waits, started, counts, wraps = [], [], [], []
    class L(Listener):
        def on_note_start(self, n):
            if n.hand == M.RIGHT: started.append(n.pitch)
        def on_waiting(self, chord):
            waits.append(e.pos); assert len(started) == len(waits) - 1
            e.release_gate()
        def on_count_in(self, x): counts.append(x)
        def on_loop_wrapped(self): wraps.append(1)
    e = Engine(s, Silent(), L()); e.gate_filter = lambda n: n.hand == M.RIGHT; e.gate_mode = True
    e.play(); t = time.time()
    while len(started) < 4 and time.time() - t < 6: time.sleep(0.01)
    e.pause(); assert len(started) >= 4 and waits == sorted(waits)
    e.gate_mode = False; e.seek(0); e.play_with_count_in(4); time.sleep(3.5); e.pause()
    assert counts == [4, 3, 2, 1, 0]
    e.seek(0); e.loop_start, e.loop_end, e.looping = 0.0, 0.5, True; e.play(); time.sleep(1.7); e.pause()
    assert 2 <= len(wraps) <= 4

def test_yin():
    for m in (33, 45, 60, 72, 88, 96):
        hz = 440 * 2 ** ((m - 69) / 12); t = np.arange(2048) / RATE
        x = sum(np.sin(2 * np.pi * hz * h * t) * np.exp(-0.5 * (h - 1)) / h for h in range(1, 7)) * 0.3
        assert yin_midi(x.astype(np.float32)) == m

def test_led_frames():
    assert Sp611e.brightness(300) == bytes([0xA0, 0x66, 1, 255])
    assert nus_frame([]) == bytes([0x7F, 1, 0, 0x6E])
    assert nus_key_byte(21, 21) == 88 and nus_key_byte(60, 21) == 49


def test_one_led_per_hand():
    from ledpiano.midi import LEFT, RIGHT
    # Chord in each hand at the same time: right shows top, left shows bottom.
    act = {60: (RIGHT, False, 1.0), 64: (RIGHT, False, 1.0), 67: (RIGHT, False, 1.01),
           36: (LEFT, False, 1.0), 43: (LEFT, False, 1.0)}
    assert one_per_hand(act) == [(36, LEFT, False), (67, RIGHT, False)]
    # Legato: the newer note wins even when it is lower.
    act = {72: (RIGHT, False, 1.0), 71: (RIGHT, False, 1.3)}
    assert one_per_hand(act) == [(71, RIGHT, False)]
    assert one_per_hand({}) == []


def test_ws2812_matches_firmware():
    """Same numbers as KeyMap.h in the ESP32 firmware and Ws2812.kt on Android."""
    from ledpiano import ws2812 as WS
    c = {"num_leds": 177, "per_metre": 144, "lowest": 21, "keys": 88, "white_mm": 23.5, "first": 0, "reversed": False}
    assert (WS.led_for_pitch(21, c), WS.led_for_pitch(60, c), WS.led_for_pitch(108, c)) == (2, 80, 174)
    assert WS.leds_needed(88, 21, 144) == 176
    r = {"num_leds": 130, "per_metre": 144, "lowest": 36, "keys": 61, "white_mm": 23.5, "first": 0, "reversed": True}
    assert (WS.led_for_pitch(36, r), WS.led_for_pitch(96, r), WS.led_for_pitch(35, r)) == (127, 9, -1)
    assert WS.pixels([(0, 0xFF0000), (300, 0x0000FF)]).hex(" ").upper() == "A5 01 0A 00 00 FF 00 00 2C 01 00 00 FF 26"
    assert WS.brightness(80).hex(" ").upper() == "A5 02 01 50 53"
    assert WS.OFF.hex(" ").upper() == "A5 01 00 01"
    # every key its own LED at 144 and 96 LEDs/m
    for d in (144, 96):
        c2 = dict(c, per_metre=d, num_leds=WS.leds_needed(88, 21, d) + 1)
        leds = [WS.led_for_pitch(p, c2) for p in range(21, 109)]
        assert -1 not in leds and len(set(leds)) == 88


def test_ws2812_controller_one_led_per_hand():
    from ledpiano.led import LedController
    from ledpiano.practice import SETTINGS_DEFAULT

    class Store:
        def __init__(self):
            self.data = dict(SETTINGS_DEFAULT, led_transport="ws2812")
        def __getitem__(self, k): return self.data[k]
        def __setitem__(self, k, v): self.data[k] = v
        def save(self): pass

    led = LedController(Store())
    sent = []
    led.state = led.CONNECTED
    led.send = sent.append
    led.key_on(60, "R")
    led.key_on(64, "R")   # chord (same moment): right hand shows its top note only
    led.key_on(48, "L")
    from ledpiano import ws2812 as WS
    last = [m for m in sent if m[1] == WS.OP_PIXELS][-1]
    assert last[0] == 0xA5 and last[1] == WS.OP_PIXELS and last[2] == 10  # two pixels: one per hand
    c = WS.config(led.s)
    idx = {last[3] | last[4] << 8, last[8] | last[9] << 8}
    assert idx == {WS.led_for_pitch(64, c), WS.led_for_pitch(48, c)}
    led.all_off()
    assert [m for m in sent if m[1] == WS.OP_PIXELS][-1] == WS.OFF
