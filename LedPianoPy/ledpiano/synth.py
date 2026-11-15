"""Sound output. Plays the recorded instruments from the sound pack (pianos, organs,
bells — see soundbank.py); falls back to a Karplus-Strong string sound if the pack is
missing. Mixed in a sounddevice callback; works silently if there is no audio device."""
from __future__ import annotations

import threading

import numpy as np

RATE = 44100

try:  # PortAudio may be missing (e.g. a headless Linux box)
    import sounddevice as sd
except Exception:  # pragma: no cover
    sd = None


def _ks(freq: float, seconds: float, ring: float, hammer: float, rng) -> np.ndarray:
    n = int(seconds * RATE)
    out = np.zeros(n, dtype=np.float32)
    for detune, mix in ((1.0, 0.6), (1.0015, 0.4)):
        period = max(2, int(RATE / (freq * detune)))
        buf = rng.uniform(-1, 1, period).astype(np.float32)
        for i in range(1, period):  # hammer softness
            buf[i] = buf[i - 1] * hammer + buf[i] * (1 - hammer)
        damp = np.exp(-1.0 / (freq * ring))
        # Vectorised KS: process one period at a time.
        y = np.empty(n, dtype=np.float32)
        cur = buf
        pos = 0
        while pos < n:
            take = min(period, n - pos)
            y[pos:pos + take] = cur[:take]
            nxt = (cur + np.roll(cur, 1)) * 0.5 * damp
            nxt[0] = (cur[0] + cur[-1]) * 0.5 * damp
            cur = nxt
            pos += take
        out += y * mix
    return out


class Synth:
    def __init__(self, bank=None, program: int = 0):
        self.bank = bank
        self._program = program
        self._sampled: list = []  # sound-pack voices: [pitch, Voice]
        self.volume = 1.0
        self.click_volume = 0.6
        self._cache: dict[tuple[int, int], np.ndarray] = {}
        self._voices: dict[int, list] = {}  # pitch -> [wave, pos, gain, target, released]
        self._clicks: list[list] = []
        self._lock = threading.Lock()
        self._stream = None
        self._rng = np.random.default_rng(1)
        t = np.arange(int(0.07 * RATE)) / RATE
        env = np.exp(-t * 60)
        self._click_hi = (np.sin(2 * np.pi * 1760 * t) * env * 0.5).astype(np.float32)
        self._click_lo = (np.sin(2 * np.pi * 1175 * t) * env * 0.35).astype(np.float32)

    @property
    def program(self) -> int:
        return self._program

    @program.setter
    def program(self, p: int):
        """Choose the instrument (General MIDI program number)."""
        self._program = p
        if self.bank is not None:
            self.bank.preload(p)

    @property
    def available(self) -> bool:
        return self._stream is not None

    def start(self):
        if sd is None or self._stream is not None:
            return
        try:
            self._stream = sd.OutputStream(samplerate=RATE, channels=1, dtype="float32",
                                           blocksize=512, callback=self._callback)
            self._stream.start()
        except Exception:
            self._stream = None

    def stop(self):
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
        with self._lock:
            self._voices.clear()

    def _wave(self, pitch: int, velocity: int) -> np.ndarray:
        layer = 0 if velocity < 64 else 1
        key = (pitch, layer)
        w = self._cache.get(key)
        if w is None:
            freq = 440.0 * 2 ** ((pitch - 69) / 12)
            ring = 1.2 + 6.5 * (1 - min(max((pitch - 21) / 87, 0), 1))
            w = _ks(freq, min(4.0, ring * 1.2), ring, 0.5 if layer == 0 else 0.2, self._rng)
            self._cache[key] = w
        return w

    def note_on(self, pitch: int, velocity: int):
        if self.bank is not None:
            from .soundbank import Voice
            v = Voice(self.bank, self._program, pitch, velocity, RATE)
            with self._lock:
                for q, old in self._sampled:
                    if q == pitch:
                        old.release()
                self._sampled.append([pitch, v])
                if len(self._sampled) > 48:
                    self._sampled.pop(0)
            return
        w = self._wave(pitch, velocity)
        v = min(max(velocity, 1), 127) / 127
        with self._lock:
            self._voices[pitch] = [w, 0, 0.0, v * v * 0.9 + 0.1 * v, False]

    def note_off(self, pitch: int):
        with self._lock:
            for q, v in self._sampled:
                if q == pitch:
                    v.release()
            v = self._voices.get(pitch)
            if v:
                v[4] = True

    def all_notes_off(self):
        with self._lock:
            for _, v in self._sampled:
                v.release()
            for v in self._voices.values():
                v[4] = True

    def click(self, accent: bool):
        with self._lock:
            self._clicks.append([self._click_hi if accent else self._click_lo, 0])

    def _callback(self, outdata, frames, _time, _status):
        mix = np.zeros(frames, dtype=np.float32)
        with self._lock:
            dead = []
            n = max(1, len(self._voices))
            poly = 1.0 / (1 + 0.22 * (n - 1))
            for pitch, v in self._voices.items():
                w, pos, gain, target, rel = v
                seg = w[pos:pos + frames]
                if len(seg) == 0:
                    dead.append(pitch)
                    continue
                goal = 0.0 if rel else target
                env = gain + (goal - gain) * (1 - np.exp(-np.arange(1, len(seg) + 1) / (60 if rel else 4)))
                mix[:len(seg)] += seg * env
                v[1] = pos + len(seg)
                v[2] = float(env[-1])
                if rel and v[2] < 1e-3:
                    dead.append(pitch)
            for p in dead:
                self._voices.pop(p, None)
            for _, v in self._sampled:
                mix += v.render(frames, RATE) * 0.7
            self._sampled = [e for e in self._sampled if not e[1].done]
            mix *= self.volume * (poly if self._voices else 1.0)
            for c in self._clicks:
                seg = c[0][c[1]:c[1] + frames]
                mix[:len(seg)] += seg * self.click_volume
                c[1] += frames
            self._clicks = [c for c in self._clicks if c[1] < len(c[0])]
        outdata[:, 0] = np.tanh(mix * 1.2) * 0.82
