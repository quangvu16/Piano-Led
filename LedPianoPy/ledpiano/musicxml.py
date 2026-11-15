"""MusicXML import (.musicxml / .xml, and compressed .mxl) – e.g. songs exported from MuseScore.

Unlike MIDI, MusicXML keeps which hand plays each note (staff 1 = right, staff 2 = left), the
finger numbers and the bars. Same rules as MusicXml.kt (Android) and MusicXml.swift (iPhone/Mac).

Handles: score-partwise, divisions changes, <chord/>, <backup>/<forward>, ties, rests, grace notes
(skipped), <sound tempo>, <time>, <fingering>, 1 part with 2 staves or 2 separate parts.
"""
from __future__ import annotations

import io
import xml.etree.ElementTree as ET
import zipfile

from . import midi as M

PPQ = 480
STEP = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def _read_root(data: bytes) -> ET.Element:
    if data[:2] == b"PK":  # .mxl (zip)
        z = zipfile.ZipFile(io.BytesIO(data))
        path = None
        try:
            c = ET.fromstring(z.read("META-INF/container.xml"))
            rf = c.find(".//rootfile")
            path = rf.get("full-path") if rf is not None else None
        except KeyError:
            pass
        if not path:
            path = next(n for n in z.namelist() if n.lower().endswith((".xml", ".musicxml")) and not n.startswith("META-INF"))
        data = z.read(path)
    root = ET.fromstring(data)
    # drop namespaces, if any
    for el in root.iter():
        if isinstance(el.tag, str) and "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    return root


def _text(el, path, default=None):
    x = el.find(path)
    return x.text.strip() if x is not None and x.text else default


def parse(data: bytes, title: str | None = None) -> M.Song:
    root = _read_root(data)
    if root.tag == "score-timewise":
        raise ValueError("MusicXML dạng 'timewise' chưa được hỗ trợ — hãy xuất lại dạng thường (partwise)")
    parts = root.findall("part")
    if not parts:
        raise ValueError("Tệp MusicXML không có phần nhạc nào")
    name = title or _text(root, "work/work-title") or _text(root, "movement-title") or "MusicXML"
    notes: list[M.Note] = []
    tempos: list[tuple[int, int]] = []
    sigs: list[tuple[int, int, int]] = []
    two_parts = len(parts) == 2

    for pi, part in enumerate(parts):
        divisions = 1
        tick = 0                 # current position in ticks
        last_start = 0           # start of the previous note (for <chord/>)
        open_ties: dict[tuple[int, int], M.Note] = {}
        staves = 1
        for measure in part.findall("measure"):
            m_start = tick
            for el in measure:
                tag = el.tag
                if tag == "attributes":
                    divisions = int(_text(el, "divisions", divisions) or divisions)
                    staves = int(_text(el, "staves", staves) or staves)
                    t = el.find("time")
                    if t is not None and pi == 0:
                        try:
                            sigs.append((m_start, int(_text(t, "beats", "4").split("+")[0]), int(_text(t, "beat-type", "4"))))
                        except ValueError:
                            pass
                elif tag in ("direction", "sound"):
                    snd = el if tag == "sound" else el.find("sound")
                    if snd is not None and snd.get("tempo") and pi == 0:
                        bpm = float(snd.get("tempo"))
                        if bpm > 0:
                            tempos.append((tick, int(60_000_000 / bpm)))
                elif tag == "backup":
                    tick -= int(_text(el, "duration", "0")) * PPQ // divisions
                elif tag == "forward":
                    tick += int(_text(el, "duration", "0")) * PPQ // divisions
                elif tag == "note":
                    if el.find("grace") is not None:
                        continue
                    dur = int(_text(el, "duration", "0")) * PPQ // divisions
                    chord = el.find("chord") is not None
                    start = last_start if chord else tick
                    if not chord:
                        last_start = tick
                        tick += dur
                    if el.find("rest") is not None or el.find("pitch") is None:
                        continue
                    pitch = (int(_text(el, "pitch/octave", "4")) + 1) * 12 + STEP.get(_text(el, "pitch/step", "C"), 0) \
                        + int(round(float(_text(el, "pitch/alter", "0"))))
                    staff = int(_text(el, "staff", "1") or 1)
                    if two_parts:
                        hand = M.RIGHT if pi == 0 else M.LEFT
                    elif staves >= 2:
                        hand = M.RIGHT if staff == 1 else M.LEFT
                    else:
                        hand = M.UNKNOWN
                    ties = {t.get("type") for t in el.findall("tie")}
                    key = (pitch, pi * 10 + staff)
                    if "stop" in ties and key in open_ties:
                        prev = open_ties[key]
                        prev.end_tick = start + dur
                        if "start" not in ties:
                            del open_ties[key]
                        continue
                    vel = 80
                    dyn = el.get("dynamics")
                    if dyn:
                        try:
                            vel = max(1, min(127, int(float(dyn) * 90 / 100)))
                        except ValueError:
                            pass
                    n = M.Note(start, start + max(dur, 1), pitch, vel, 0 if hand != M.LEFT else 1, 0)
                    n.hand = hand
                    f = _text(el, "notations/technical/fingering")
                    if f and f[:1].isdigit():
                        n.finger = int(f[:1])
                    notes.append(n)
                    if "start" in ties:
                        open_ties[key] = n
    if not notes:
        raise ValueError("Tệp MusicXML không có nốt nhạc nào")
    tempos.sort()
    if not tempos or tempos[0][0] > 0:
        tempos.insert(0, (0, 500_000))
    sigs = sorted(set(sigs)) or [(0, 4, 4)]
    if sigs[0][0] > 0:
        sigs.insert(0, (0, 4, 4))
    song = M.Song(name, PPQ, notes, tempos, sigs)
    return M.finish(song)


def load(path: str, title: str | None = None) -> M.Song:
    with open(path, "rb") as f:
        return parse(f.read(), title)


def is_musicxml(path: str) -> bool:
    return path.lower().endswith((".musicxml", ".mxl", ".xml"))
