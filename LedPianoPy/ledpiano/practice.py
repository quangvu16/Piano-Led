"""Scoring, coaching (fading lights, accuracy-gated tempo), practice stats with
forgiving streaks, and the lesson path."""
from __future__ import annotations

import datetime as dt
import json
import os
from bisect import bisect_left
from dataclasses import dataclass

from .midi import LEFT, RIGHT, Note, Song

HIT, WRONG, IGNORED = "hit", "wrong", "ignored"


@dataclass
class PassResult:
    total: int
    hits: int
    wrong: int
    mean_offset_ms: int

    @property
    def accuracy(self) -> int:
        return 0 if self.total == 0 else self.hits * 100 // self.total

    @property
    def clean(self) -> bool:
        return self.total > 0 and self.accuracy >= 95 and self.wrong == 0


class ScoreKeeper:
    """A note is hit when its key is pressed within 0.3 s (real time) of its start."""

    def __init__(self):
        self.start_pass([])

    def start_pass(self, notes: list[Note]):
        self.expected = sorted(notes, key=lambda n: n.start)
        self._starts = [n.start for n in self.expected]
        self.hit = [False] * len(self.expected)
        self.wrong, self._off, self._offn = 0, 0.0, 0
        self.last_offset_ms = 0.0      # timing of the last correct key (real ms, + = late)
        self.last_note = None          # the expected note it matched

    def on_key(self, pitch: int, song_sec: float, speed: int, tol: float = 0.3) -> str:
        if not self.expected:
            return IGNORED
        t = tol * speed / 100
        best, bd = -1, 1e9
        i = bisect_left(self._starts, song_sec - t)
        while i < len(self.expected) and self.expected[i].start <= song_sec + t:
            n = self.expected[i]
            if not self.hit[i] and n.pitch == pitch and abs(n.start - song_sec) < bd:
                best, bd = i, abs(n.start - song_sec)
            i += 1
        if best >= 0:
            self.hit[best] = True
            off = (song_sec - self.expected[best].start) * 100 / speed
            self._off += off
            self._offn += 1
            self.last_offset_ms, self.last_note = off * 1000, self.expected[best]
            return HIT
        self.wrong += 1
        return WRONG

    def count_wrong(self):
        self.wrong += 1

    def mark_chord_done(self, chord: list[Note]):
        for n in chord:
            for i, e in enumerate(self.expected):
                if e is n:
                    self.hit[i] = True

    def missed(self, up_to: float) -> list[Note]:
        """Expected notes up to ``up_to`` that were not played (for hot spots)."""
        return [n for i, n in enumerate(self.expected) if n.start <= up_to and not self.hit[i]]

    def finish_pass(self, up_to: float) -> PassResult:
        total = hits = 0
        for i, n in enumerate(self.expected):
            if n.start > up_to:
                break
            total += 1
            hits += self.hit[i]
        mean = int(self._off / self._offn * 1000) if self._offn else 0
        return PassResult(total, hits, self.wrong, mean)


class Coach:
    """Fading guidance (Hsieh et al., CHI 2026) and accuracy-gated tempo (Duke et al. 2009)."""
    FULL, FADE, OFF = "full", "fade", "off"

    def __init__(self):
        self._lights = self.FULL
        self.guidance = 1.0
        self.clean_streak = self._ok = self._bad = 0

    @property
    def lights(self):
        return self._lights

    @lights.setter
    def lights(self, v):
        self._lights = v
        self.guidance = 0.0 if v == self.OFF else 1.0
        self._ok = self._bad = 0

    def on_correct(self) -> bool:
        self._bad = 0
        if self._lights != self.FADE:
            return False
        self._ok += 1
        if self._ok >= 4 and self.guidance > 0:
            self._ok = 0
            self.guidance = max(0.0, self.guidance - 0.25)
            return True
        return False

    def on_error(self) -> bool:
        self._ok = 0
        if self._lights != self.FADE:
            return False
        self._bad += 1
        if self._bad >= 2 and self.guidance < 1:
            self._bad, self.guidance = 0, 1.0
            return True
        return False

    def on_pass(self, r: PassResult, speed: int, auto: bool):
        if r.total == 0:
            return None
        self.clean_streak = self.clean_streak + 1 if r.clean else 0
        if not auto:
            return None
        if self.clean_streak >= 3 and speed < 100:
            self.clean_streak = 0
            return min(100, speed + 10)
        if r.accuracy < 70 and speed > 25:
            self.clean_streak = 0
            return max(25, speed - 10)
        return None

    def reset(self):
        self.clean_streak = self._ok = self._bad = 0


def data_dir() -> str:
    d = os.path.join(os.path.expanduser("~"), ".ledpiano")
    os.makedirs(d, exist_ok=True)
    return d


class JsonStore:
    def __init__(self, name: str, default: dict):
        self.path = os.path.join(data_dir(), name)
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data = {**default, **json.load(f)}
        except Exception:
            self.data = dict(default)

    def save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def __getitem__(self, k):
        return self.data[k]

    def __setitem__(self, k, v):
        self.data[k] = v
        self.save()


SETTINGS_DEFAULT = {
    "label_mode": "c", "practice_mode": "normal", "lights_mode": "full", "count_in": True,
    "metronome": False, "auto_speed": False, "show_staff": False, "show_fingers": True,
    "hand_split": 60, "mic_input": False, "daily_goal": 10, "speed": 100, "volume": 100,
    "color_left": "#3B82F6", "color_right": "#F97316", "led_transport": "legacy_nus",
    "led_brightness": 180, "led_length": 88, "organ_offset": 21, "led_address": None,
    "led_name": None, "auto_reconnect": True, "recent": [], "program": 0,
    # WS2812B strip (ESP32 + FastLED "LED Piano Strip"): LEDs/metre, piano keys, LEDs (0 = automatic),
    # first LED (alignment) and strip direction.
    "ws_density": 144, "ws_keys": 88, "ws_num_leds": 0, "ws_first_led": 0, "ws_reversed": False,
    # Learning features: next note glows early, red/green/orange flashes, brightness follows loudness.
    "led_preview": False, "led_feedback": True, "led_dynamics": False,
}


class Stats:
    MIN_DAY = 60

    def __init__(self):
        self.store = JsonStore("stats.json", {"days": {}, "songs": {}})

    @staticmethod
    def today() -> int:
        return dt.date.today().toordinal()

    def add_practice(self, key: str, seconds: int, speed: int):
        if seconds <= 0:
            return
        d = self.store.data["days"]
        t = str(self.today())
        d[t] = d.get(t, 0) + seconds
        for k in [k for k in d if int(k) < self.today() - 90]:
            del d[k]
        s = self.store.data["songs"].setdefault(key, {})
        s["last"] = self.today()
        s["sec"] = s.get("sec", 0) + seconds
        s["speed"] = max(s.get("speed", 0), speed)
        self.store.save()

    def today_seconds(self) -> int:
        return self.store.data["days"].get(str(self.today()), 0)

    def last_seven(self) -> list[int]:
        d, t = self.store.data["days"], self.today()
        return [d.get(str(t - 6 + i), 0) for i in range(7)]

    def streak(self) -> int:
        """One missed day per 7 is forgiven (Silverman & Barasch 2022)."""
        d = self.store.data["days"]
        ok = lambda day: d.get(str(day), 0) >= self.MIN_DAY
        day = self.today()
        if not ok(day):
            day -= 1
        streak, forgiven = 0, 10 ** 9
        while streak < 3650:
            if ok(day):
                streak += 1
            elif streak > 0 and forgiven - day >= 7 and ok(day - 1):
                forgiven = day
            else:
                break
            day -= 1
        return streak

    def record(self, key: str) -> dict:
        return self.store.data["songs"].get(key, {})

    def record_result(self, key: str, r: PassResult, finished: bool, speed: int):
        s = self.store.data["songs"].setdefault(key, {})
        if r.total:
            s["best"] = max(s.get("best", -1), r.accuracy)
        if r.clean:
            s["clean"] = s.get("clean", 0) + 1
        if finished:
            s["done"] = True
            s["speed"] = max(s.get("speed", 0), speed)
        self.store.save()

    def mark_completed(self, key: str, speed: int):
        s = self.store.data["songs"].setdefault(key, {})
        s["done"], s["speed"] = True, max(s.get("speed", 0), speed)
        self.store.save()


@dataclass
class Lesson:
    path: str
    key: str
    title: str
    subtitle: str
    level: int
    hands: str
    fingers_r: list[int] | None
    fingers_l: list[int] | None


class Lessons:
    def __init__(self, assets: str):
        self.assets = assets
        self.levels: list[tuple[int, str, list[Lesson]]] = []
        try:
            with open(os.path.join(assets, "lessons", "lessons.json"), encoding="utf-8") as f:
                doc = json.load(f)
        except Exception:
            return
        for lv in doc["levels"]:
            items = []
            for s in lv["songs"]:
                fg = s.get("fingers", {})
                items.append(Lesson(os.path.join(assets, s["file"]), "asset://" + s["file"], s["title"],
                                    s.get("subtitle", ""), lv["level"], s.get("hands", "LR"),
                                    fg.get("R"), fg.get("L")))
            self.levels.append((lv["level"], lv["title"], items))

    def all(self) -> list[Lesson]:
        return [l for _, _, ls in self.levels for l in ls]

    def find(self, key: str) -> Lesson | None:
        return next((l for l in self.all() if l.key == key), None)

    def next(self, key: str) -> Lesson | None:
        a = self.all()
        for i, l in enumerate(a):
            if l.key == key:
                return a[i + 1] if i + 1 < len(a) else None
        return None

    @staticmethod
    def fingering(lesson: Lesson, song: Song) -> dict[int, int]:
        """Finger per note (keyed by id(note))."""
        out = {}
        for hand, fingers in ((RIGHT, lesson.fingers_r), (LEFT, lesson.fingers_l)):
            if not fingers:
                continue
            ns = sorted((n for n in song.notes if n.hand == hand), key=lambda n: (n.start_tick, n.pitch))
            for n, f in zip(ns, fingers):
                if 1 <= f <= 5:
                    out[id(n)] = f
        return out
