"""The learner's piano: USB / Bluetooth MIDI keyboards (via mido + python-rtmidi)
and an experimental microphone listener (YIN pitch detection, one note at a time)."""
from __future__ import annotations

import math
import threading
import time

import numpy as np

NONE, MIDI, MIC = "none", "midi", "mic"

try:
    import mido
except Exception:  # pragma: no cover
    mido = None
try:
    import sounddevice as sd
except Exception:  # pragma: no cover
    sd = None


class InputHub:
    """Fans note events out to listeners: on_note_on(p, v), on_note_off(p), on_input_state(src, name)."""

    def __init__(self):
        self.source, self.device = NONE, None
        self.listeners: list = []
        self.down: dict[int, int] = {}

    @property
    def connected(self):
        return self.source != NONE

    def note_on(self, p, v):
        self.down[p] = v
        for l in list(self.listeners):
            l.on_note_on(p, v)

    def note_off(self, p):
        if self.down.pop(p, None) is None:
            return
        for l in list(self.listeners):
            l.on_note_off(p)

    def set_state(self, src, name):
        self.source, self.device = src, name
        if src == NONE:
            for p in list(self.down):
                self.note_off(p)
        for l in list(self.listeners):
            l.on_input_state(src, name)


class MidiInput:
    """Polls for MIDI input ports every 2 s and opens the first one (USB or a
    Bluetooth MIDI keyboard paired in the system's Bluetooth/Audio MIDI settings)."""

    def __init__(self, hub: InputHub, post):
        self.hub, self.post = hub, post
        self.port = None
        self.name = None
        self._run = False

    @property
    def supported(self) -> bool:
        if mido is None:
            return False
        try:
            mido.get_input_names()
            return True
        except Exception:
            return False

    def start(self):
        if self._run or not self.supported:
            return
        self._run = True
        threading.Thread(target=self._watch, daemon=True).start()

    def stop(self):
        self._run = False
        self._close()

    def _watch(self):
        while self._run:
            try:
                names = [n for n in mido.get_input_names() if "Through" not in n]
            except Exception:
                names = []
            if self.port is None and names and self.hub.source != MIC:
                try:
                    self.port = mido.open_input(names[0], callback=self._on_msg)
                    self.name = names[0]
                    self.post(lambda: self.hub.set_state(MIDI, names[0]))
                except Exception:
                    self.port = None
            elif self.port is not None and self.name not in names:
                self._close()
            time.sleep(2)

    def _close(self):
        p, self.port = self.port, None
        if p is not None:
            try:
                p.close()
            except Exception:
                pass
            if self.hub.source == MIDI:
                self.post(lambda: self.hub.set_state(NONE, None))

    def _on_msg(self, msg):
        if msg.type == "note_on" and msg.velocity > 0:
            self.post(lambda: self.hub.note_on(msg.note, msg.velocity))
        elif msg.type in ("note_off", "note_on"):
            self.post(lambda: self.hub.note_off(msg.note))


RATE, FRAME, HOP = 22050, 2048, 512


def yin_midi(x: np.ndarray, threshold: float = 0.15) -> int:
    """YIN (de Cheveigné & Kawahara 2002). Returns a MIDI note or -1."""
    half = len(x) // 2
    min_tau, max_tau = RATE // 2100, min(half - 1, RATE // 27)
    d = np.zeros(max_tau + 1)
    xh = x[:half]
    for tau in range(1, max_tau + 1):
        diff = xh - x[tau:tau + half]
        d[tau] = np.dot(diff, diff)
    cm = np.ones_like(d)
    run = np.cumsum(d[1:])
    with np.errstate(divide="ignore", invalid="ignore"):
        cm[1:] = np.where(run > 0, d[1:] * np.arange(1, max_tau + 1) / run, 1)
    tau = min_tau
    while tau <= max_tau:
        if cm[tau] < threshold:
            while tau + 1 <= max_tau and cm[tau + 1] < cm[tau]:
                tau += 1
            break
        tau += 1
    if tau > max_tau:
        return -1
    better = float(tau)
    if 2 <= tau < max_tau:
        s0, s1, s2 = cm[tau - 1], cm[tau], cm[tau + 1]
        den = 2 * (2 * s1 - s2 - s0)
        if den:
            better = tau + (s2 - s0) / den
    midi = round(69 + 12 * math.log2(RATE / better / 440))
    return midi if 21 <= midi <= 108 else -1


class MicInput:
    def __init__(self, hub: InputHub, post):
        self.hub, self.post = hub, post
        self.stream = None
        self._buf = np.zeros(FRAME, dtype=np.float32)
        self._cur, self._cand, self._cnt, self._quiet = -1, -1, 0, 0

    def start(self) -> bool:
        if sd is None or self.stream is not None:
            return self.stream is not None
        try:
            self.stream = sd.InputStream(samplerate=RATE, channels=1, dtype="float32",
                                         blocksize=HOP, callback=self._cb)
            self.stream.start()
        except Exception:
            self.stream = None
            return False
        self.post(lambda: self.hub.set_state(MIC, "Micro"))
        return True

    def stop(self):
        s, self.stream = self.stream, None
        if s is not None:
            try:
                s.stop()
                s.close()
            except Exception:
                pass
            if self._cur >= 0:
                p = self._cur
                self.post(lambda: self.hub.note_off(p))
            self._cur = -1
            if self.hub.source == MIC:
                self.post(lambda: self.hub.set_state(NONE, None))

    def _cb(self, indata, frames, _t, _s):
        x = indata[:, 0]
        self._buf = np.concatenate([self._buf[frames:], x])
        rms = float(np.sqrt(np.mean(self._buf ** 2)))
        p = yin_midi(self._buf) if rms > 0.012 else -1
        if p < 0:
            self._quiet += 1
            if self._quiet >= 3 and self._cur >= 0:
                c = self._cur
                self.post(lambda: self.hub.note_off(c))
                self._cur = -1
            self._cand, self._cnt = -1, 0
            return
        self._quiet = 0
        if p == self._cand:
            self._cnt += 1
        else:
            self._cand, self._cnt = p, 1
        if self._cnt >= 2 and p != self._cur:
            old, self._cur = self._cur, p
            vel = max(20, min(127, int(rms * 900)))
            if old >= 0:
                self.post(lambda: self.hub.note_off(old))
            self.post(lambda: self.hub.note_on(p, vel))
