"""MIDI file loading, hand assignment and the beat grid (same rules as the Android app)."""
from __future__ import annotations

from bisect import bisect_left
from dataclasses import dataclass, field

import mido

LEFT, RIGHT, UNKNOWN = "L", "R", "?"
PIANO_LOWEST, PIANO_HIGHEST, MIDDLE_C = 21, 108, 60


@dataclass(eq=False)
class Note:
    start_tick: int
    end_tick: int
    pitch: int
    velocity: int
    track: int
    channel: int
    start: float = 0.0  # seconds
    end: float = 0.0
    hand: str = UNKNOWN
    finger: int = 0  # 1–5 when the score says (MusicXML <fingering>), else 0

    @property
    def is_black(self) -> bool:
        return self.pitch % 12 in (1, 3, 6, 8, 10)


@dataclass
class Song:
    title: str
    ppq: int
    notes: list[Note]
    tempos: list[tuple[int, int]]  # (tick, microseconds per quarter)
    time_sigs: list[tuple[int, int, int]]  # (tick, numerator, denominator)
    duration: float = 0.0
    by_time: list[Note] = field(default_factory=list)
    multi_instrument: bool = False

    def tick_to_sec(self, tick: int) -> float:
        sec, last, us = 0.0, 0, 500_000
        for t, u in self.tempos:
            if t >= tick:
                break
            if t > last:
                sec += (t - last) * us / self.ppq / 1e6
                last = t
            us = u
        return sec + (tick - last) * us / self.ppq / 1e6

    def measure_ticks(self) -> list[int]:
        max_tick = max((n.end_tick for n in self.notes), default=0)
        out, i, tick = [], 0, self.time_sigs[0][0]
        while tick <= max_tick and len(out) < 100_000:
            out.append(tick)
            _, num, den = self.time_sigs[i]
            length = num * 4 * self.ppq // den
            nxt = self.time_sigs[i + 1] if i + 1 < len(self.time_sigs) else None
            if nxt and nxt[0] < tick + length:
                tick, i = nxt[0], i + 1
            else:
                tick += length
        return out

    def measure_secs(self) -> list[float]:
        return [s for s in (self.tick_to_sec(t) for t in self.measure_ticks()) if s <= self.duration]


def finish(song: Song) -> Song:
    """Seconds, duration and time order for a freshly built song (MIDI or MusicXML)."""
    for n in song.notes:
        if n.end_tick <= n.start_tick:
            n.end_tick = n.start_tick + 1
        n.start, n.end = song.tick_to_sec(n.start_tick), song.tick_to_sec(n.end_tick)
    song.duration = max(n.end for n in song.notes)
    song.by_time = sorted(song.notes, key=lambda n: n.start)
    song.multi_instrument = len({n.track for n in song.notes}) > 2
    return song


def load(path: str, title: str | None = None) -> Song:
    """Opens a MIDI file, or a MusicXML score (.musicxml / .xml / .mxl)."""
    if path.lower().endswith((".musicxml", ".mxl", ".xml")):
        from . import musicxml
        return musicxml.load(path, title)
    mf = mido.MidiFile(path)
    notes: list[Note] = []
    tempos: list[tuple[int, int]] = []
    sigs: list[tuple[int, int, int]] = []
    for ti, track in enumerate(mf.tracks):
        tick = 0
        open_notes: dict[tuple[int, int], list[Note]] = {}
        for msg in track:
            tick += msg.time
            if msg.type == "set_tempo":
                tempos.append((tick, msg.tempo))
            elif msg.type == "time_signature":
                sigs.append((tick, msg.numerator, msg.denominator))
            elif msg.type == "note_on" and msg.velocity > 0:
                n = Note(tick, tick, msg.note, msg.velocity, ti, msg.channel)
                open_notes.setdefault((msg.note, msg.channel), []).append(n)
                notes.append(n)
            elif msg.type in ("note_off", "note_on"):
                stack = open_notes.get((msg.note, msg.channel))
                if stack:
                    stack.pop(0).end_tick = tick
        for stack in open_notes.values():
            for n in stack:
                n.end_tick = max(n.end_tick, n.start_tick + mf.ticks_per_beat // 2)
    if not notes:
        raise ValueError("Tệp MIDI không có nốt nhạc nào")
    tempos.sort()
    if not tempos or tempos[0][0] > 0:
        tempos.insert(0, (0, 500_000))
    sigs.sort()
    if not sigs:
        sigs = [(0, 4, 4)]
    song = Song(title or path.rsplit("/", 1)[-1], mf.ticks_per_beat, notes, tempos, sigs)
    return finish(song)


def uses_pitch_split(song: Song) -> bool:
    return len({n.track for n in song.notes}) != 2


def assign_hands(song: Song, split: int = MIDDLE_C) -> None:
    """Two tracks: lower-average track = left hand. Otherwise split by pitch.
    Scores that already say the hand (MusicXML staves) keep it."""
    if song.notes and all(n.hand != UNKNOWN for n in song.notes):
        return
    tracks: dict[int, list[Note]] = {}
    for n in song.notes:
        tracks.setdefault(n.track, []).append(n)
    if len(tracks) == 2:
        avg = {t: sum(x.pitch for x in ns) / len(ns) for t, ns in tracks.items()}
        low = min(avg, key=avg.get)
        for t, ns in tracks.items():
            for n in ns:
                n.hand = LEFT if t == low else RIGHT
    else:
        for n in song.notes:
            n.hand = LEFT if n.pitch < split else RIGHT


class BeatGrid:
    """Beat times (seconds) for the metronome and the count-in."""

    def __init__(self, song: Song):
        secs, down = [], []
        measures = song.measure_ticks()
        for i, m in enumerate(measures):
            ts = [s for s in song.time_sigs if s[0] <= m]
            _, num, den = ts[-1] if ts else song.time_sigs[0]
            beat = max(1, song.ppq * 4 // den)
            nxt = measures[i + 1] if i + 1 < len(measures) else m + beat * num
            for k in range(num):
                t = m + k * beat
                if t >= nxt:
                    break
                s = song.tick_to_sec(t)
                if s > song.duration + 0.01:
                    break
                secs.append(s)
                down.append(k == 0)
        self.secs, self.down = secs, down
        self.beats_per_measure = max(2, min(8, song.time_sigs[0][1]))

    def lower_bound(self, sec: float) -> int:
        return bisect_left(self.secs, sec)

    def beat_length_at(self, sec: float) -> float:
        if len(self.secs) < 2:
            return 0.5
        i = min(max(self.lower_bound(sec), 1), len(self.secs) - 1)
        d = self.secs[i] - self.secs[i - 1]
        return d if d > 0.05 else 0.5


LETTERS = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]
SOLFEGE = ["Đô", "Đô♯", "Rê", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si"]


def note_name(pitch: int, mode: str) -> str | None:
    pc = pitch % 12
    if mode == "letters":
        return LETTERS[pc]
    if mode == "solfege":
        return SOLFEGE[pc]
    if mode == "c":
        return f"C{pitch // 12 - 1}" if pc == 0 else None
    return None


def key_label(pitch: int, mode: str) -> str | None:
    base = note_name(pitch, mode)
    if base and pitch % 12 == 0 and mode != "c":
        return f"{base}{pitch // 12 - 1}"
    return base


def octave(pitch: int) -> int:
    return pitch // 12 - 1
