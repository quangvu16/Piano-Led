"""Playback engine: speed 25–100 %, A–B loop, count-in, metronome and the
Wait/Step "gate" that holds time before each chord the learner must play.
Same behaviour as the Android PlaybackEngine; listener callbacks run on the
engine thread."""
from __future__ import annotations

import threading
import time
from bisect import bisect_left

from .midi import BeatGrid, Note, Song

MIN_SPEED, MAX_SPEED = 25, 100
CHORD_WINDOW = 0.06
EPS = 1e-4


class Listener:
    def on_note_start(self, note: Note): ...
    def on_note_end(self, note: Note): ...
    def on_time(self, pos: float): ...
    def on_state(self, playing: bool): ...
    def on_song_end(self): ...
    def on_waiting(self, chord: list[Note]): ...
    def on_count_in(self, beats_left: int): ...
    def on_loop_wrapped(self): ...


class Engine:
    def __init__(self, song: Song, synth, listener: Listener):
        self.song, self.synth, self.l = song, synth, listener
        self.notes = song.by_time
        self._starts = [n.start for n in self.notes]
        self.beats = BeatGrid(song)
        self._speed = 100
        self.loop_start: float | None = None
        self.loop_end: float | None = None
        self.looping = False
        self.metronome = False
        self._gate = False
        self.gate_filter = lambda n: True
        self.mute_filter = lambda n: False
        self.playing = False
        self.pos = 0.0
        self.waiting: list[Note] | None = None
        self._released = False
        self._floor = -1.0
        self._cursor = 0
        self._beat = 0
        self._active: list[Note] = []
        self._count_left = 0
        self._count_total = 0
        self._count_timer = 0.0
        self._lock = threading.RLock()
        self._thread: threading.Thread | None = None
        self._stop = False

    # ---- properties ----
    @property
    def speed(self) -> int:
        return self._speed

    @speed.setter
    def speed(self, v: int):
        self._speed = max(MIN_SPEED, min(MAX_SPEED, int(v)))

    @property
    def gate_mode(self) -> bool:
        return self._gate

    @gate_mode.setter
    def gate_mode(self, v: bool):
        self._gate = v
        if v:
            self._floor = self.pos - EPS
        else:
            self.waiting, self._released = None, False

    @property
    def counting_in(self) -> bool:
        return self._count_left > 0

    # ---- control ----
    def play(self):
        if self.playing:
            return
        if self.pos >= self.song.duration:
            self.seek(0)
        self.playing, self._stop = True, False
        self.l.on_state(True)
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def play_with_count_in(self, count: int | None = None):
        if self.playing:
            return
        self._count_left = self._count_total = max(1, min(8, count or self.beats.beats_per_measure))
        self._count_timer = 0.0
        self.play()

    def _join(self):
        t = self._thread
        if t and t is not threading.current_thread():
            t.join(0.3)
        self._thread = None

    def pause(self):
        if not self.playing:
            return
        self.playing, self._stop = False, True
        self._join()
        self._count_left = 0
        self._release_active()
        self.l.on_state(False)

    def stop(self):
        self.playing, self._stop = False, True
        self._join()
        self._count_left = 0
        self._release_active()
        with self._lock:
            self.pos, self._cursor, self._beat = 0.0, 0, 0
        self._floor, self.waiting, self._released = -1.0, None, False
        self.synth.all_notes_off()
        self.l.on_time(0.0)
        self.l.on_state(False)

    def seek(self, sec: float):
        with self._lock:
            self._release_active()
            self.pos = max(0.0, min(sec, self.song.duration))
            self._cursor = bisect_left(self._starts, self.pos)
            self._beat = self.beats.lower_bound(self.pos)
        self._floor, self.waiting, self._released = self.pos - EPS, None, False
        self.synth.all_notes_off()
        self.l.on_time(self.pos)

    def release_gate(self):
        if self.waiting is not None:
            self._released = True

    # ---- internals ----
    def _release_active(self):
        with self._lock:
            for n in self._active:
                self.synth.note_off(n.pitch)
                self.l.on_note_end(n)
            self._active.clear()

    def _next_gate(self, floor: float):
        i = bisect_left(self._starts, floor + 1e-9)
        f = self.gate_filter
        while i < len(self.notes):
            n = self.notes[i]
            if n.start > floor and f(n):
                return n.start
            i += 1
        return None

    def _chord_at(self, t: float) -> list[Note]:
        i = bisect_left(self._starts, t - 1e-9)
        out = []
        while i < len(self.notes) and self.notes[i].start <= t + CHORD_WINDOW:
            if self.gate_filter(self.notes[i]):
                out.append(self.notes[i])
            i += 1
        return out

    def _run(self):
        last = time.perf_counter()
        last_ui = 0.0
        while not self._stop:
            now = time.perf_counter()
            dt, last = now - last, now

            if self._count_left > 0:
                self._count_timer -= dt
                if self._count_timer <= 0:
                    left = self._count_left
                    self.synth.click(left == self._count_total)
                    self.l.on_count_in(left)
                    self._count_left = left - 1
                    self._count_timer += self.beats.beat_length_at(self.pos) * 100 / self._speed
                    if self._count_left == 0:
                        end = time.perf_counter() + self._count_timer
                        while time.perf_counter() < end and not self._stop:
                            time.sleep(0.01)
                        last = time.perf_counter()
                        self.l.on_count_in(0)
                time.sleep(0.008)
                continue

            if self.waiting is not None:
                if self._released:
                    held = self.waiting
                    self.waiting, self._released = None, False
                    if held:
                        self._floor = held[0].start + CHORD_WINDOW
                else:
                    if now - last_ui > 0.033:
                        last_ui = now
                        self.l.on_time(self.pos)
                    time.sleep(0.008)
                    continue

            new = self.pos + dt * self._speed / 100
            if self._gate:
                g = self._next_gate(self._floor)
                if g is not None and new >= g:
                    new = g - EPS
                    chord = self._chord_at(g)
                    if chord:
                        self.waiting = chord
                        self.l.on_waiting(chord)
                    else:
                        self._floor = g
            self.pos = new

            if self.looping and self.loop_end is not None and self.pos >= self.loop_end:
                ls = self.loop_start or 0.0
                self.pos = ls
                self._release_active()
                with self._lock:
                    self._cursor = bisect_left(self._starts, ls)
                    self._beat = self.beats.lower_bound(ls)
                self._floor, self.waiting = ls - EPS, None
                self.l.on_loop_wrapped()

            with self._lock:
                b = self.beats
                while self._beat < len(b.secs) and b.secs[self._beat] <= self.pos:
                    if self.metronome and self.pos - b.secs[self._beat] < 0.25:
                        self.synth.click(b.down[self._beat])
                    self._beat += 1
                while self._cursor < len(self.notes) and self.notes[self._cursor].start <= self.pos:
                    n = self.notes[self._cursor]
                    if self.pos - n.start < 1.0:
                        if not self.mute_filter(n):
                            self.synth.note_on(n.pitch, n.velocity)
                        self._active.append(n)
                        self.l.on_note_start(n)
                    self._cursor += 1
                keep = []
                for n in self._active:
                    if n.end <= self.pos:
                        self.synth.note_off(n.pitch)
                        self.l.on_note_end(n)
                    else:
                        keep.append(n)
                self._active = keep

            if now - last_ui > 0.033:
                last_ui = now
                self.l.on_time(self.pos)

            if self.pos >= self.song.duration and not self._active:
                self._stop, self.playing = True, False
                self.l.on_song_end()
                self.l.on_state(False)
                break
            time.sleep(0.008)
