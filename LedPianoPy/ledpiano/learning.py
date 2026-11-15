"""Learning helpers shared by the player (same rules in the Android and iPhone/Mac apps).

* HotSpots      – remembers in which bar mistakes happen ("Luyện chỗ khó", Duke et al.: find the exact
                  spot, fix it at once, repeat until the mistake stops).
* timing_flash  – green / orange flash colour for a correct key played on time / early or late.
* Dynamics      – LED brightness from the note's loudness and "play softer / louder" advice.
* Recorder      – records what the learner plays; saved as a normal MIDI file (opens like any song).
* daily_plan    – today's 3 short tasks: warm-up, review an older song, one new lesson.
"""
from __future__ import annotations

from bisect import bisect_right

# ---------------------------------------------------------------- hot spots

HOTSPOT_SPEED = 65          # start the hard bar at 65 % (then +10 % after 3 clean loops)


def bar_of(measures: list[float], sec: float) -> int:
    """Index of the bar that contains ``sec`` (measures = bar start times)."""
    return max(0, bisect_right(measures, sec + 1e-6) - 1)


class HotSpots:
    """Mistakes per bar, per song, kept in the stats store under "hot"."""

    def __init__(self, stats_store):
        self.st = stats_store

    def _bars(self, key: str) -> dict:
        return self.st.data.setdefault("hot", {}).setdefault(key, {})

    def add_miss(self, key: str, bar: int, n: int = 1):
        b = self._bars(key)
        b[str(bar)] = b.get(str(bar), 0) + n
        self.st.save()

    def add_clean(self, key: str, bar: int):
        """A clean play of the bar lowers its count; at 0 it is no longer a hot spot."""
        b = self._bars(key)
        if str(bar) in b:
            b[str(bar)] -= 1
            if b[str(bar)] <= 0:
                del b[str(bar)]
            self.st.save()

    def counts(self, key: str) -> dict[int, int]:
        return {int(k): v for k, v in self.st.data.get("hot", {}).get(key, {}).items() if v > 0}

    def worst(self, key: str) -> int | None:
        c = self.counts(key)
        return max(c, key=lambda k: (c[k], -k)) if c else None


def loop_for_bar(measures: list[float], duration: float, bar: int) -> tuple[float, float]:
    """Loop range for one bar (two bars when a bar is shorter than 1.5 s)."""
    a = measures[bar] if bar < len(measures) else 0.0
    end_bar = bar + 1
    b = measures[end_bar] if end_bar < len(measures) else duration
    if b - a < 1.5 and end_bar + 1 < len(measures):
        b = measures[end_bar + 1]
    return a, min(b, duration)


# ---------------------------------------------------------------- feedback colours

RED, GREEN, ORANGE = 0xFF0000, 0x00D040, 0xFF8C00
GOOD_MS = 90  # real-time window counted as "on time"


def timing_flash(offset_ms: float) -> int:
    """Green when within ±90 ms of the beat, otherwise orange (early or late)."""
    return GREEN if abs(offset_ms) <= GOOD_MS else ORANGE


# ---------------------------------------------------------------- dynamics

def dyn_scale(velocity: int) -> float:
    """LED brightness for a note's loudness: soft 30 % … loud 100 %."""
    return 0.3 + 0.7 * max(0, min(127, velocity)) / 127


class Dynamics:
    """Compares how hard the learner plays (MIDI velocity) with the song."""

    MIN_NOTES, LIMIT = 6, 20

    def __init__(self):
        self.diffs: list[int] = []

    def reset(self):
        self.diffs.clear()

    def add(self, expected: int, played: int):
        if played > 0 and expected > 0:
            self.diffs.append(played - expected)

    def advice(self) -> str | None:
        """'softer' / 'louder' when the learner is consistently off, else None."""
        if len(self.diffs) < self.MIN_NOTES:
            return None
        mean = sum(self.diffs) / len(self.diffs)
        if mean > self.LIMIT:
            return "softer"
        if mean < -self.LIMIT:
            return "louder"
        return None


ADVICE_VI = {"softer": "Chơi nhẹ tay hơn một chút", "louder": "Chơi mạnh tay hơn một chút"}


# ---------------------------------------------------------------- recording

class Recorder:
    """Records note on/off (seconds since start) and writes a standard MIDI file."""

    def __init__(self):
        self.events: list[tuple[float, int, int]] = []  # (sec, pitch, velocity; 0 = off)
        self.active = False
        self._t0 = 0.0

    def start(self, now: float):
        self.events.clear()
        self.active = True
        self._t0 = now

    def note(self, now: float, pitch: int, velocity: int):
        if self.active:
            self.events.append((max(0.0, now - self._t0), pitch, velocity))

    def stop(self) -> int:
        """Stops; returns the number of notes played."""
        self.active = False
        return sum(1 for _, _, v in self.events if v > 0)

    def midi_bytes(self, bpm: int = 120) -> bytes:
        return midi_file(self.events, bpm)


def midi_file(events: list[tuple[float, int, int]], bpm: int = 120, ppq: int = 480) -> bytes:
    """Type-0 standard MIDI file (one track, piano) from (sec, pitch, velocity) events."""
    us = 60_000_000 // bpm
    trk = bytearray()

    def var(n: int):
        buf = [n & 0x7F]
        n >>= 7
        while n:
            buf.insert(0, (n & 0x7F) | 0x80)
            n >>= 7
        trk.extend(buf)

    var(0)
    trk += bytes([0xFF, 0x51, 3, (us >> 16) & 0xFF, (us >> 8) & 0xFF, us & 0xFF])
    var(0)
    trk += bytes([0xFF, 0x58, 4, 4, 2, 24, 8])
    last = 0
    open_notes: set[int] = set()
    for sec, pitch, vel in sorted(events, key=lambda e: (e[0], e[2] == 0)):
        tick = int(round(sec * ppq * 1_000_000 / us))
        if vel == 0 and pitch not in open_notes:
            continue
        var(max(0, tick - last))
        last = max(last, tick)
        if vel > 0:
            open_notes.add(pitch)
            trk += bytes([0x90, pitch & 0x7F, min(127, vel)])
        else:
            open_notes.discard(pitch)
            trk += bytes([0x80, pitch & 0x7F, 0])
    for pitch in sorted(open_notes):  # close anything still held
        var(ppq // 2)
        trk += bytes([0x80, pitch & 0x7F, 0])
    var(0)
    trk += bytes([0xFF, 0x2F, 0])
    head = b"MThd" + (6).to_bytes(4, "big") + (0).to_bytes(2, "big") + (1).to_bytes(2, "big") + ppq.to_bytes(2, "big")
    return head + b"MTrk" + len(trk).to_bytes(4, "big") + bytes(trk)


# ---------------------------------------------------------------- daily plan

def daily_plan(lessons: list, songs: dict, today: int) -> list[tuple[str, object]]:
    """Today's plan: [("warmup", lesson), ("review", lesson), ("new", lesson)] (any may be missing).

    lessons: all lessons in order (objects with .key and .title); songs: stats per song key
    ({"last": day, "done": bool, …}); today: date.toordinal()."""
    out: list[tuple[str, object]] = []
    warm = next((l for l in lessons if "hanon" in l.title.lower()), None) or (lessons[0] if lessons else None)
    if warm:
        out.append(("warmup", warm))
    practised = [l for l in lessons if l is not warm and songs.get(l.key, {}).get("last") is not None
                 and songs[l.key]["last"] < today]
    if practised:
        # The song not played for the longest time (spacing practice out helps it stick).
        out.append(("review", min(practised, key=lambda l: songs[l.key]["last"])))
    new = next((l for l in lessons if l is not warm and not songs.get(l.key, {}).get("done")), None)
    if new and all(new is not x for _, x in out):
        out.append(("new", new))
    return out


PLAN_VI = {"warmup": "Khởi động", "review": "Ôn lại", "new": "Bài mới"}
