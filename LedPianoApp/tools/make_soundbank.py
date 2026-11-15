"""Builds the LED Piano sound pack from a General MIDI SoundFont (GeneralUser GS).

Extracts 24 instruments (8 pianos, 8 organs, 8 bells/mallets), keeps one velocity
layer and one channel, keeps the original sample rate, shortens unlooped notes, and
compresses with IMA ADPCM (4 bits/sample). Output:
  soundbank.json  — instruments, key zones, loops, envelopes, sample table
  soundbank.bin   — ADPCM sample data
Usage: python3 make_soundbank.py GeneralUser-GS.sf2 OUT_DIR
"""
import json
import struct
import sys

import numpy as np

RATE = 22050
MAX_UNLOOPED_SEC = 2.6
FADE_SEC = 0.25

INSTRUMENTS = [
    # program, group, Vietnamese name, English name
    (0, "piano", "Đàn piano cơ (Grand)", "Acoustic Grand Piano"),
    (1, "piano", "Piano sáng", "Bright Acoustic Piano"),
    (2, "piano", "Piano điện Grand", "Electric Grand Piano"),
    (3, "piano", "Piano quán bar (Honky-tonk)", "Honky-tonk Piano"),
    (4, "piano", "Piano điện Rhodes", "Electric Piano 1"),
    (5, "piano", "Piano điện FM", "Electric Piano 2"),
    (6, "piano", "Đàn Harpsichord", "Harpsichord"),
    (7, "piano", "Đàn Clavinet", "Clavinet"),
    (16, "organ", "Organ Hammond", "Drawbar Organ"),
    (17, "organ", "Organ gõ", "Percussive Organ"),
    (18, "organ", "Organ Rock", "Rock Organ"),
    (19, "organ", "Organ nhà thờ", "Church Organ"),
    (20, "organ", "Organ hơi (Reed)", "Reed Organ"),
    (21, "organ", "Đàn Accordion", "Accordion"),
    (22, "organ", "Kèn Harmonica", "Harmonica"),
    (23, "organ", "Accordion Tango", "Tango Accordion"),
    (8, "bells", "Đàn Celesta", "Celesta"),
    (9, "bells", "Chuông Glockenspiel", "Glockenspiel"),
    (10, "bells", "Hộp nhạc", "Music Box"),
    (11, "bells", "Vibraphone", "Vibraphone"),
    (12, "bells", "Marimba", "Marimba"),
    (13, "bells", "Mộc cầm (Xylophone)", "Xylophone"),
    (14, "bells", "Chuông ống", "Tubular Bells"),
    (15, "bells", "Đàn tam thập lục (Dulcimer)", "Dulcimer"),
]


def chunks(data, start, end):
    p = start
    while p + 8 <= end:
        cid = data[p:p + 4].decode("latin1")
        size = struct.unpack_from("<I", data, p + 4)[0]
        yield cid, p + 8, size
        p += 8 + size + (size & 1)


def parse_sf2(path):
    data = open(path, "rb").read()
    assert data[:4] == b"RIFF" and data[8:12] == b"sfbk"
    out = {}
    for cid, off, size in chunks(data, 12, len(data)):
        if cid == "LIST":
            kind = data[off:off + 4].decode()
            for sub, so, ss in chunks(data, off + 4, off + size):
                out[(kind, sub)] = (so, ss)
    smpl_off, smpl_size = out[("sdta", "smpl")]
    smpl = np.frombuffer(data, dtype="<i2", count=smpl_size // 2, offset=smpl_off)

    def table(name, fmt, size):
        o, s = out[("pdta", name)]
        return [struct.unpack_from(fmt, data, o + i * size) for i in range(s // size)]

    phdr = table("phdr", "<20sHHHIII", 38)
    pbag = table("pbag", "<HH", 4)
    pgen = table("pgen", "<HH", 4)
    inst = table("inst", "<20sH", 22)
    ibag = table("ibag", "<HH", 4)
    igen = table("igen", "<HH", 4)
    shdr = table("shdr", "<20sIIIIIBbHH", 46)
    return smpl, phdr, pbag, pgen, inst, ibag, igen, shdr


def gens_of(bags, gens, b0, b1):
    """List of zones; each zone is a dict gen->raw uint16 value."""
    zones = []
    for b in range(b0, b1):
        g0, g1 = bags[b][0], bags[b + 1][0]
        z = {}
        for g in range(g0, g1):
            op, amt = gens[g]
            z[op] = amt
        zones.append(z)
    return zones


def s16(v):
    return v - 65536 if v >= 32768 else v


def rng(v):
    return v & 0xFF, v >> 8


def tc_to_sec(tc):
    return 0.0 if tc <= -12000 else 2 ** (tc / 1200)


def collect(sf):
    smpl, phdr, pbag, pgen, inst, ibag, igen, shdr = sf
    presets = {}
    for i in range(len(phdr) - 1):
        name, prog, bank, bag0 = phdr[i][0], phdr[i][1], phdr[i][2], phdr[i][3]
        if bank == 0:
            presets[prog] = (name.split(b"\0")[0].decode("latin1"), bag0, phdr[i + 1][3])
    return presets


ADDITIVE = (34, 35, 36, 37, 38, 40, 48, 51, 52)


def preset_zones(sf, prog):
    smpl, phdr, pbag, pgen, inst, ibag, igen, shdr = sf
    _, b0, b1 = collect(sf)[prog]
    pz = gens_of(pbag, pgen, b0, b1)
    pglobal = pz[0] if pz and 41 not in pz[0] else {}
    result = []
    for z in pz:
        if 41 not in z:
            continue
        pgens = {**pglobal, **z}
        ii = z[41]
        iz = gens_of(ibag, igen, inst[ii][1], inst[ii + 1][1])
        iglobal = iz[0] if iz and 53 not in iz[0] else {}
        for zz in iz:
            if 53 not in zz:
                continue
            g = {**iglobal, **zz}
            klo, khi = rng(g.get(43, 0x7F00))
            vlo, vhi = rng(g.get(44, 0x7F00))
            pklo, pkhi = rng(pgens.get(43, 0x7F00))
            pvlo, pvhi = rng(pgens.get(44, 0x7F00))
            klo, khi = max(klo, pklo), min(khi, pkhi)
            vlo, vhi = max(vlo, pvlo), min(vhi, pvhi)
            if klo > khi or vlo > vhi:
                continue
            vals = {k: s16(g.get(k, d)) for k, d in
                    ((34, -12000), (35, -12000), (36, -12000), (37, 0), (38, -12000), (40, 0), (48, 0), (51, 0), (52, 0))}
            for k in ADDITIVE:
                if k in pgens:
                    vals[k] += s16(pgens[k])
            result.append(dict(klo=klo, khi=khi, vlo=vlo, vhi=vhi, sample=g[53], g=g, v=vals))
    return result


def pick_layer(zones):
    """One velocity layer (the one holding velocity 100) and one channel per key range."""
    out, seen = [], set()
    for z in sorted(zones, key=lambda z: (z["klo"], -(z["vlo"] <= 100 <= z["vhi"]), -z["vhi"])):
        key = (z["klo"], z["khi"])
        if key in seen:
            continue
        cand = [c for c in zones if (c["klo"], c["khi"]) == key]
        best = [c for c in cand if c["vlo"] <= 100 <= c["vhi"]] or sorted(cand, key=lambda c: -c["vhi"])
        # Prefer mono / left samples over right channel.
        seen.add(key)
        out.append(best[0])
    return out


def ima_encode(x):
    """IMA ADPCM encoder for int16 numpy array. Returns (bytes, first_pred, first_index)."""
    steps = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73,
             80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494,
             544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
             2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
             12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767]
    idx_tab = [-1, -1, -1, -1, 2, 4, 6, 8]
    pred, index = 0, 0
    nib = []
    for s in x.tolist():
        step = steps[index]
        diff = s - pred
        code = 0
        if diff < 0:
            code = 8
            diff = -diff
        delta = step >> 3
        if diff >= step:
            code |= 4; diff -= step; delta += step
        if diff >= step >> 1:
            code |= 2; diff -= step >> 1; delta += step >> 1
        if diff >= step >> 2:
            code |= 1; delta += step >> 2
        pred = pred - delta if code & 8 else pred + delta
        pred = max(-32768, min(32767, pred))
        index = max(0, min(88, index + idx_tab[code & 7]))
        nib.append(code)
    if len(nib) & 1:
        nib.append(0)
    a = np.array(nib, dtype=np.uint8)
    return (a[0::2] | (a[1::2] << 4)).tobytes()


def main(sf_path, out_dir):
    sf = parse_sf2(sf_path)
    smpl, *_, shdr = sf
    presets = collect(sf)
    samples = {}  # (sf sample id, start_trim) -> entry
    blob = bytearray()
    insts = []
    for prog, group, vi, en in INSTRUMENTS:
        zones = pick_layer(preset_zones(sf, prog))
        out_z = []
        for z in zones:
            sid = z["sample"]
            name, start, end, ls, le, rate, root, corr, link, stype = shdr[sid]
            g = z["g"]
            start += s16(g.get(0, 0)) + 32768 * s16(g.get(4, 0))
            end += s16(g.get(1, 0)) + 32768 * s16(g.get(12, 0))
            ls += s16(g.get(2, 0)) + 32768 * s16(g.get(45, 0))
            le += s16(g.get(3, 0)) + 32768 * s16(g.get(50, 0))
            mode = g.get(54, 0) & 3
            looped = mode in (1, 3) and le > ls + 8
            key = (sid, start, end, ls, le, looped)
            if key not in samples:
                raw = smpl[start:end].astype(np.float64)
                ratio = 1.0  # keep the recording's own sample rate
                if looped:
                    raw = raw[: le - start + 1]
                else:
                    raw = raw[: int(MAX_UNLOOPED_SEC * rate)]
                n = max(2, int(len(raw) * ratio))
                y = np.interp(np.arange(n) / ratio, np.arange(len(raw)), raw)
                if not looped and len(raw) >= int(MAX_UNLOOPED_SEC * rate) - 1:
                    f = int(FADE_SEC * rate)
                    y[-f:] *= np.linspace(1, 0, f)
                y = np.clip(y, -32768, 32767).astype(np.int16)
                data = ima_encode(y)
                entry = dict(offset=len(blob), length=int(len(y)), bytes=len(data), rate=int(rate))
                if looped:
                    entry["loop"] = [int((ls - start) * ratio), int((le - start) * ratio)]
                blob += data
                samples[key] = (len(samples), entry)
            idx, entry = samples[key]
            v = z["v"]
            root_key = g.get(58, root) & 0xFF if 58 in g else root
            out_z.append(dict(
                lo=z["klo"], hi=z["khi"], sample=idx, root=root_key if root_key <= 127 else 60,
                tune=int(v[51] * 100 + v[52] + corr),
                atten=round(max(0, v[48]) / 10.0, 1),
                attack=round(tc_to_sec(v[34]), 4), hold=round(tc_to_sec(v[35]), 4),
                decay=round(tc_to_sec(v[36]), 4), key_decay=v[40], sustain=round(max(0, v[37]) / 10.0, 1),
                release=round(min(4.0, tc_to_sec(v[38])), 4),
                loop=bool(entry.get("loop")),
            ))
        out_z.sort(key=lambda z: z["lo"])
        insts.append(dict(program=prog, group=group, name=vi, english=en,
                          sf_name=presets.get(prog, ("?",))[0], zones=out_z))
        print(f"{prog:3} {en:22} zones={len(out_z):3}  pack={len(blob)/1e6:.2f} MB")
    table = [None] * len(samples)
    for _, (idx, entry) in samples.items():
        table[idx] = entry
    doc = dict(format="ledpiano-soundbank-1", rate=RATE, codec="ima-adpcm-mono",
               source="GeneralUser GS v2.0.3 by S. Christian Collins (schristiancollins.com) — free to use",
               instruments=insts, samples=table)
    import os
    os.makedirs(out_dir, exist_ok=True)
    json.dump(doc, open(os.path.join(out_dir, "soundbank.json"), "w", encoding="utf-8"), ensure_ascii=False)
    open(os.path.join(out_dir, "soundbank.bin"), "wb").write(bytes(blob))
    print("samples", len(table), "bin", len(blob), "bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
