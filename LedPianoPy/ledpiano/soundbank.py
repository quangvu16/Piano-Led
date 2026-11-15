"""Recorded instruments (pianos, organs, bells) from the bundled sound pack
(soundbank.json + soundbank.bin, made by tools/make_soundbank.py from GeneralUser GS).

Each instrument is a list of key zones; a zone plays one recorded sample,
re-pitched to the requested key, with an optional sustain loop and an
attack/hold/decay/sustain/release volume envelope."""
from __future__ import annotations

import json
import os
import threading

import numpy as np

STEPS = np.array([7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73,
                  80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494,
                  544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
                  2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
                  12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767])
INDEX = [-1, -1, -1, -1, 2, 4, 6, 8]

GROUPS = [("piano", "Đàn piano"), ("organ", "Đàn organ"), ("bells", "Chuông & gõ")]


def ima_decode(data: bytes, n: int) -> np.ndarray:
    """IMA ADPCM (low nibble first), predictor and index start at 0."""
    b = np.frombuffer(data, dtype=np.uint8)
    codes = np.empty(len(b) * 2, dtype=np.uint8)
    codes[0::2] = b & 0x0F
    codes[1::2] = b >> 4
    out = np.empty(n, dtype=np.float32)
    pred, index = 0, 0
    steps = STEPS.tolist()
    for i, c in enumerate(codes[:n].tolist()):
        step = steps[index]
        delta = step >> 3
        if c & 4:
            delta += step
        if c & 2:
            delta += step >> 1
        if c & 1:
            delta += step >> 2
        pred = pred - delta if c & 8 else pred + delta
        if pred > 32767:
            pred = 32767
        elif pred < -32768:
            pred = -32768
        index += INDEX[c & 7]
        if index < 0:
            index = 0
        elif index > 88:
            index = 88
        out[i] = pred
    return out / 32768.0


class SoundBank:
    def __init__(self, folder: str):
        with open(os.path.join(folder, "soundbank.json"), encoding="utf-8") as f:
            self.doc = json.load(f)
        with open(os.path.join(folder, "soundbank.bin"), "rb") as f:
            self.blob = f.read()
        self.instruments = {i["program"]: i for i in self.doc["instruments"]}
        self._pcm: dict[int, np.ndarray] = {}
        self._lock = threading.Lock()

    @property
    def credit(self) -> str:
        return self.doc.get("source", "")

    def listing(self):
        """[(group title, [(program, name)])] in display order."""
        return [(title, [(i["program"], i["name"]) for i in self.doc["instruments"] if i["group"] == g])
                for g, title in GROUPS]

    def pcm(self, sid: int) -> np.ndarray:
        with self._lock:
            a = self._pcm.get(sid)
            if a is None:
                s = self.doc["samples"][sid]
                a = ima_decode(self.blob[s["offset"]:s["offset"] + s["bytes"]], s["length"])
                self._pcm[sid] = a
            return a

    def preload(self, program: int):
        for z in self.instruments.get(program, {"zones": []})["zones"]:
            self.pcm(z["sample"])

    def zone(self, program: int, key: int):
        inst = self.instruments.get(program) or self.instruments[0]
        zs = inst["zones"]
        for z in zs:
            if z["lo"] <= key <= z["hi"]:
                return z
        return min(zs, key=lambda z: min(abs(key - z["lo"]), abs(key - z["hi"])))


class Voice:
    __slots__ = ("pcm", "pos", "step", "loop", "gain", "t", "z", "released", "rel_t", "rel_db", "done", "decay")

    def __init__(self, bank: SoundBank, program: int, key: int, vel: int, out_rate: int):
        z = bank.zone(program, key)
        s = bank.doc["samples"][z["sample"]]
        self.pcm = bank.pcm(z["sample"])
        self.z = z
        self.pos = 0.0
        cents = (key - z["root"]) * 100 + z["tune"]
        self.step = 2 ** (cents / 1200) * s["rate"] / out_rate
        self.loop = s.get("loop") if z["loop"] else None
        v = max(1, min(127, vel)) / 127
        self.gain = 10 ** (-z["atten"] / 20) * v * v * 1.6
        self.t = 0.0
        self.released = False
        self.rel_t = 0.0
        self.rel_db = 0.0
        self.done = False
        self.decay = z["decay"] * 2 ** (z.get("key_decay", 0) * (60 - key) / 1200)

    def env_db(self, t):
        """Volume envelope in dB (0 = full) at times t (array) before release."""
        z = self.z
        a, h, d, sus = z["attack"], z["hold"], max(1e-3, self.decay), z["sustain"]
        db = np.where(t < a, 20 * np.log10(np.maximum(t / max(a, 1e-4), 1e-5)), 0.0)
        dd = np.clip((t - a - h) / d, 0, 1) * sus
        return np.where(t >= a + h, -dd, db)

    def render(self, n: int, rate: int) -> np.ndarray:
        idx = self.pos + self.step * np.arange(n)
        if self.loop:
            ls, le = self.loop
            span = max(1, le - ls)
            idx = np.where(idx >= le, ls + np.mod(idx - ls, span), idx)
        else:
            if idx[0] >= len(self.pcm) - 1:
                self.done = True
                return np.zeros(n, np.float32)
            idx = np.minimum(idx, len(self.pcm) - 1.001)
        i0 = idx.astype(np.int64)
        frac = (idx - i0).astype(np.float32)
        i1 = np.minimum(i0 + 1, len(self.pcm) - 1)
        y = self.pcm[i0] * (1 - frac) + self.pcm[i1] * frac
        nxt = self.pos + self.step * n
        if self.loop and nxt >= self.loop[1]:
            ls, le = self.loop
            nxt = ls + (nxt - ls) % max(1, le - ls)
        self.pos = nxt
        tt = self.t + np.arange(n) / rate
        db = self.env_db(tt)
        if self.released:
            rel = max(0.02, self.z["release"])
            rt = self.rel_t + np.arange(n) / rate
            db = np.minimum(db, self.rel_db - 100 * rt / rel)
            self.rel_t += n / rate
            if db[-1] < -90:
                self.done = True
        elif db[-1] < -90 and self.t > 0.05:
            self.done = True
        self.t += n / rate
        return (y * self.gain * 10 ** (db / 20)).astype(np.float32)

    def release(self):
        if not self.released:
            self.released = True
            self.rel_db = float(self.env_db(np.array([self.t]))[0])
            self.rel_t = 0.0
