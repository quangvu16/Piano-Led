"""New learning features: hot spots, flashes, dynamics, recording, daily plan, MusicXML, ESP32 messages."""
import io
import os
import sys
import time
import zipfile

import mido

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from ledpiano import learning as L  # noqa: E402
from ledpiano import midi as M  # noqa: E402
from ledpiano import musicxml as X  # noqa: E402
from ledpiano import wled as WL  # noqa: E402
from ledpiano import ws2812 as WS  # noqa: E402
from ledpiano.led import LedController  # noqa: E402
from ledpiano.practice import SETTINGS_DEFAULT  # noqa: E402


class Store:
    def __init__(self, **kw):
        self.data = dict(SETTINGS_DEFAULT, **kw)
    def __getitem__(self, k): return self.data[k]
    def __setitem__(self, k, v): self.data[k] = v
    def save(self): pass


# ---- 1. hot spots
def test_hot_spots_find_worst_bar_and_heal():
    st = Store()
    h = L.HotSpots(st)
    measures = [0.0, 2.0, 4.0, 6.0]
    for sec in (2.5, 3.9, 4.1):            # 2 mistakes in bar 1, 1 in bar 2
        h.add_miss("song", L.bar_of(measures, sec))
    assert h.counts("song") == {1: 2, 2: 1} and h.worst("song") == 1
    assert L.loop_for_bar(measures, 8.0, 1) == (2.0, 4.0)
    h.add_clean("song", 1); h.add_clean("song", 1)
    assert h.worst("song") == 2
    assert L.loop_for_bar([0.0, 1.0, 2.0, 3.0], 4.0, 0) == (0.0, 2.0)   # short bars: two bars


# ---- 3. flashes / timing
def test_timing_colours():
    assert L.timing_flash(40) == L.GREEN and L.timing_flash(-85) == L.GREEN
    assert L.timing_flash(150) == L.ORANGE and L.timing_flash(-200) == L.ORANGE


# ---- 4. dynamics
def test_dynamics_advice():
    d = L.Dynamics()
    for _ in range(5):
        d.add(60, 110)
    assert d.advice() is None              # too few notes yet
    d.add(60, 110)
    assert d.advice() == "softer"
    d.reset()
    for _ in range(8):
        d.add(100, 50)
    assert d.advice() == "louder"
    assert abs(L.dyn_scale(127) - 1.0) < 1e-9 and abs(L.dyn_scale(0) - 0.3) < 1e-9


# ---- 5. recording
def test_recording_writes_valid_midi(tmp_path):
    r = L.Recorder()
    r.start(100.0)
    r.note(100.5, 60, 90); r.note(101.0, 64, 70); r.note(101.2, 60, 0); r.note(101.5, 64, 0)
    r.note(102.0, 67, 80)                  # still held when stopped: closed automatically
    assert r.stop() == 3
    p = tmp_path / "rec.mid"
    p.write_bytes(r.midi_bytes(120))
    song = M.load(str(p))
    assert [n.pitch for n in song.by_time] == [60, 64, 67]
    assert abs(song.by_time[0].start - 0.5) < 0.01 and abs(song.by_time[1].start - 1.0) < 0.01
    assert abs(song.by_time[0].end - 1.2) < 0.01


# ---- 6. daily plan
class Les:
    def __init__(self, key, title):
        self.key, self.title = key, title


def test_daily_plan():
    ls = [Les("a", "Năm ngón tay phải"), Les("b", "Mary"), Les("h", "Bài tập Hanon số 1"), Les("c", "Ngôi sao")]
    songs = {"a": {"last": 100, "done": True}, "b": {"last": 105, "done": True}}
    plan = L.daily_plan(ls, songs, today=110)
    assert [(k, l.key) for k, l in plan] == [("warmup", "h"), ("review", "a"), ("new", "c")]
    assert [k for k, _ in L.daily_plan(ls, {}, 110)] == ["warmup", "new"]


# ---- 7. MusicXML
XML = """<?xml version="1.0" encoding="UTF-8"?>
<score-partwise version="3.1">
  <work><work-title>Bài thử</work-title></work>
  <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
  <part id="P1">
    <measure number="1">
      <attributes><divisions>2</divisions><time><beats>3</beats><beat-type>4</beat-type></time><staves>2</staves></attributes>
      <direction><sound tempo="90"/></direction>
      <note><pitch><step>C</step><octave>5</octave></pitch><duration>2</duration><staff>1</staff>
        <notations><technical><fingering>1</fingering></technical></notations></note>
      <note><chord/><pitch><step>E</step><octave>5</octave></pitch><duration>2</duration><staff>1</staff></note>
      <note><pitch><step>F</step><alter>1</alter><octave>5</octave></pitch><duration>2</duration><staff>1</staff>
        <tie type="start"/></note>
      <note><rest/><duration>2</duration><staff>1</staff></note>
      <backup><duration>6</duration></backup>
      <note><pitch><step>C</step><octave>3</octave></pitch><duration>6</duration><staff>2</staff>
        <notations><technical><fingering>5</fingering></technical></notations></note>
    </measure>
    <measure number="2">
      <note><pitch><step>F</step><alter>1</alter><octave>5</octave></pitch><duration>2</duration><staff>1</staff>
        <tie type="stop"/></note>
      <note><grace/><pitch><step>D</step><octave>5</octave></pitch><duration>0</duration><staff>1</staff></note>
      <note><pitch><step>G</step><octave>5</octave></pitch><duration>4</duration><staff>1</staff></note>
    </measure>
  </part>
</score-partwise>"""


def check_song(s):
    ps = [(n.pitch, n.hand, n.finger) for n in sorted(s.notes, key=lambda n: (n.start_tick, n.pitch))]
    assert ps == [(48, "L", 5), (72, "R", 1), (76, "R", 0), (78, "R", 0), (79, "R", 0)]
    fs = next(n for n in s.notes if n.pitch == 78)
    assert abs(fs.start - 60 / 90) < 0.01                     # beat 2 at 90 bpm
    assert abs(fs.end - 4 * 60 / 90) < 0.01                   # tied into bar 2: ends after its first beat
    assert s.time_sigs[0] == (0, 3, 4) and s.title == "Bài thử"
    assert abs(s.measure_secs()[1] - 3 * 60 / 90) < 0.01
    M.assign_hands(s, 60)  # keeps the staff hands
    assert next(n for n in s.notes if n.pitch == 48).hand == "L"


def test_musicxml_plain_and_mxl(tmp_path):
    check_song(X.parse(XML.encode()))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("META-INF/container.xml",
                   '<container><rootfiles><rootfile full-path="score.xml"/></rootfiles></container>')
        z.writestr("score.xml", XML)
    p = tmp_path / "song.mxl"
    p.write_bytes(buf.getvalue())
    check_song(M.load(str(p)))   # through the normal song loader


# ---- ESP32 messages
def parse_all(msgs):
    p = WL.MessageParser()
    out = []
    for m in msgs:
        for b in m:
            if p.feed(b):
                out.append((p.op, bytes(p.data)))
    return out


def test_esp32_config_expect_and_song_messages():
    c = WS.config({"ws_density": 30, "ws_keys": 61, "ws_num_leds": 0, "ws_first_led": -2, "ws_reversed": True})
    (op, d), = parse_all([WS.config_message(c, 0x0A66D6, 0xF8B84E)])
    assert op == WS.OP_CONFIG and d[0] | d[1] << 8 == c["num_leds"] and d[2] == 30 and d[3] == 36 and d[4] == 61
    assert (d[5] | d[6] << 8) == 0xFFFE and d[7] == 1 and d[8:11] == bytes([0x0A, 0x66, 0xD6])
    (op, d), = parse_all([WS.expect_message([64, 60, 60])])
    assert op == WS.OP_EXPECT and d == bytes([60, 64])
    notes = [(i * 0.25, 60 + i % 12, "L" if i % 2 else "R") for i in range(120)]
    msgs = parse_all(WS.song_messages(notes))
    assert msgs[0] == (WS.OP_SONG_BEGIN, bytes([120, 0])) and msgs[-1] == (WS.OP_SONG_END, bytes([120, 0]))
    data = [m for m in msgs if m[0] == WS.OP_SONG_DATA]
    assert len(data) == 3 and all(len(d) <= 2 + 5 * 49 for _, d in data)
    _, d = data[1]
    i = d[0] | d[1] << 8
    ms = d[2] | d[3] << 8 | d[4] << 16
    assert i == 49 and ms == 49 * 250 and d[5] == 60 + 49 % 12 and d[6] == 1


# ---- LED behaviour (pixel strip)
def ws_led(**kw):
    led = LedController(Store(led_transport="ws2812", **kw))
    sent = []
    led.state = led.CONNECTED
    led.send = sent.append
    return led, sent


def pixels_of(sent):
    msg = [m for m in sent if m[1] == WS.OP_PIXELS][-1]
    return {msg[3 + i] | msg[4 + i] << 8: int.from_bytes(msg[5 + i:8 + i], "big") for i in range(0, msg[2], 5)}


def test_led_flash_red_then_clears():
    led, sent = ws_led()
    c = WS.config(led.s)
    led.key_on(64, "R")
    led.flash(62, L.RED, 60)                   # wrong key D while E is lit
    px = pixels_of(sent)
    assert px[WS.led_for_pitch(62, c)] == L.RED and WS.led_for_pitch(64, c) in px
    time.sleep(0.15)
    px = pixels_of(sent)
    assert WS.led_for_pitch(62, c) not in px and len(px) == 1


def test_led_preview_is_dim_and_optional():
    led, sent = ws_led(led_preview=True)
    c = WS.config(led.s)
    led.key_on(60, "R")
    led.set_preview([(64, "R")])
    px = pixels_of(sent)
    full = WS.rgb_hex(led.s["color_right"])
    assert px[WS.led_for_pitch(64, c)] == WS.scale(full, 0.18) and px[WS.led_for_pitch(60, c)] == full
    led2, sent2 = ws_led()                     # default: off → only one LED
    led2.key_on(60, "R")
    led2.set_preview([(64, "R")])
    assert list(pixels_of(sent2)) == [WS.led_for_pitch(60, WS.config(led2.s))]


def test_led_blinks_when_next_note_shares_the_led():
    led, sent = ws_led(ws_density=30, ws_num_leds=37)
    c = WS.config(led.s)
    assert WS.led_for_pitch(60, c) == WS.led_for_pitch(62, c)
    led.key_on(60, "R"); led.key_off(60)
    n = len(sent)
    led.key_on(62, "R")                        # same LED, new note: off for 70 ms, then on
    pix_msgs = [m for m in sent[n:] if m[1] == WS.OP_PIXELS]
    assert pix_msgs[0] == WS.OFF
    time.sleep(0.15)
    assert pixels_of(sent) == {WS.led_for_pitch(62, c): WS.rgb_hex(led.s["color_right"])}


def test_led_dynamics_brightness():
    led, sent = ws_led(led_dynamics=True)
    led.key_on(60, "R", vel=20)
    full = WS.rgb_hex(led.s["color_right"])
    assert pixels_of(sent)[WS.led_for_pitch(60, WS.config(led.s))] == WS.scale(full, L.dyn_scale(20))


def test_esp32_expect_message_follows_lit_keys():
    led, sent = ws_led()
    led.key_on(60, "R"); led.key_on(48, "L")
    exp = [m for m in sent if m[1] == WS.OP_EXPECT][-1]
    assert sorted(exp[3:3 + exp[2]]) == [48, 60]


def test_piano_notes_from_esp32_reach_listeners():
    led, _ = ws_led()
    got = []
    led.piano_listeners.append(lambda p, v: got.append((p, v)))
    led._on_rx(WL_note(60, 90) + WL_note(60, 0) + bytes([0xA5, WS.OP_STATUS, 5, 1, 192, 168, 1, 9, WS.OP_STATUS ^ 5 ^ 1 ^ 192 ^ 168 ^ 1 ^ 9]))
    assert got == [(60, 90), (60, 0)] and led.esp32_status == (1, "192.168.1.9")


def WL_note(p, v):
    return bytes([0xA5, WS.OP_NOTE, 2, p, v, WS.OP_NOTE ^ 2 ^ p ^ v])
