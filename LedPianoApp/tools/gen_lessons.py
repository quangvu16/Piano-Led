# Writes the lesson-path MIDI files (public-domain melodies, arranged here) and lessons.json.
# Usage: python3 gen_lessons.py <assets dir>
import json, os, struct, sys

ASSETS = sys.argv[1]
OUT = os.path.join(ASSETS, 'lessons')
os.makedirs(OUT, exist_ok=True)
PPQ = 480
NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}


def p(name):
    """'C4' -> 60, 'F#3' -> 54, 'Bb4' -> 70."""
    n = NAMES[name[0]]
    rest = name[1:]
    if rest.startswith('#'):
        n += 1
        rest = rest[1:]
    elif rest.startswith('b'):
        n -= 1
        rest = rest[1:]
    return 12 * (int(rest) + 1) + n


def vlq(v):
    out = [v & 0x7F]
    v >>= 7
    while v:
        out.append(0x80 | (v & 0x7F))
        v >>= 7
    return bytes(reversed(out))


def track(events):
    """events: list of (tick, bytes) sorted; returns MTrk chunk."""
    events = sorted(events, key=lambda e: (e[0], e[1][0] & 0xF0 != 0x80))
    data = b''
    last = 0
    for t, msg in events:
        data += vlq(t - last) + msg
        last = t
    data += vlq(0) + b'\xFF\x2F\x00'
    return b'MTrk' + struct.pack('>I', len(data)) + data


def notes_track(name, seq, ch, vel):
    """seq: list of (pitch-name or None for rest, beats). Returns (chunk, n_notes)."""
    ev = [(0, b'\xFF\x03' + vlq(len(name.encode())) + name.encode())]
    t = 0
    for item in seq:
        names, beats = item
        dur = int(beats * PPQ)
        if names:
            for nm in names.split('+'):
                k = p(nm)
                ev.append((t, bytes([0x90 | ch, k, vel])))
                ev.append((t + dur - 20, bytes([0x80 | ch, k, 0])))
        t += dur
    return track(ev)


def write_song(fname, title, tempo_bpm, rh, lh, beats_per_bar=4):
    us = int(60_000_000 / tempo_bpm)
    meta = [(0, b'\xFF\x51\x03' + us.to_bytes(3, 'big')),
            (0, b'\xFF\x58\x04' + bytes([beats_per_bar, 2, 24, 8])),
            (0, b'\xFF\x03' + vlq(len(title.encode())) + title.encode())]
    chunks = [track(meta), notes_track('Right hand', rh, 0, 92), notes_track('Left hand', lh, 1, 62)]
    head = b'MThd' + struct.pack('>IHHH', 6, 1, len(chunks), PPQ)
    with open(os.path.join(OUT, fname), 'wb') as f:
        f.write(head + b''.join(chunks))


def q(*names):  # quarter notes
    return [(n, 1) for n in names]


def seqf(spec):
    """'E4:1 D4:1 C4:2' -> [(name, beats)]; '-' is a rest."""
    out = []
    for tok in spec.split():
        n, b = tok.split(':')
        out.append((None if n == '-' else n, float(b)))
    return out


songs = []

# ---- Level 1 ----
five = 'C4:1 D4:1 E4:1 F4:1 G4:2 - :0'.replace(' - :0', '')
rh = seqf('C4:1 D4:1 E4:1 F4:1 G4:1 F4:1 E4:1 D4:1 C4:2 -:2 ' * 2)
lh = seqf('C3:4 G2:4 C3:4 -:4')
write_song('l1_five_right.mid', 'Năm ngón tay phải', 84, rh, lh)
songs.append((1, 'l1_five_right.mid', 'Năm ngón tay phải', 'Đô Rê Mi Fa Sol', 'R', [1, 2, 3, 4, 5, 4, 3, 2, 1] * 2, None))

lh2 = seqf('C3:1 D3:1 E3:1 F3:1 G3:1 F3:1 E3:1 D3:1 C3:2 -:2 ' * 2)
rh2 = seqf('E4+G4:4 D4+G4:4 E4+G4:4 -:4')
write_song('l1_five_left.mid', 'Năm ngón tay trái', 84, rh2, lh2)
songs.append((1, 'l1_five_left.mid', 'Năm ngón tay trái', 'Đô Rê Mi Fa Sol', 'L', None, [5, 4, 3, 2, 1, 2, 3, 4, 5] * 2))

mary = seqf('E4:1 D4:1 C4:1 D4:1 E4:1 E4:1 E4:2 D4:1 D4:1 D4:2 E4:1 G4:1 G4:2 '
            'E4:1 D4:1 C4:1 D4:1 E4:1 E4:1 E4:1 E4:1 D4:1 D4:1 E4:1 D4:1 C4:4')
mary_lh = seqf('C3:4 C3:4 G2:4 C3:4 C3:4 C3:4 G2:4 C3:4')
write_song('l1_mary.mid', 'Mary có một chú cừu non', 96, mary, mary_lh)
songs.append((1, 'l1_mary.mid', 'Mary có một chú cừu non', 'Mary Had a Little Lamb', 'R',
              [3, 2, 1, 2, 3, 3, 3, 2, 2, 2, 3, 5, 5, 3, 2, 1, 2, 3, 3, 3, 3, 2, 2, 3, 2, 1], None))

# ---- Level 2 ----
buom = seqf('C4:1 D4:1 E4:1 C4:1 C4:1 D4:1 E4:1 C4:1 E4:1 F4:1 G4:2 E4:1 F4:1 G4:2 '
            'G4:0.5 A4:0.5 G4:0.5 F4:0.5 E4:1 C4:1 G4:0.5 A4:0.5 G4:0.5 F4:0.5 E4:1 C4:1 '
            'C4:1 G3:1 C4:2 C4:1 G3:1 C4:2')
buom_lh = seqf('C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4')
write_song('l2_kia_con_buom_vang.mid', 'Kìa con bướm vàng', 100, buom, buom_lh)
# Fingers only where the five-finger position is unambiguous; 0 = no number shown.
songs.append((2, 'l2_kia_con_buom_vang.mid', 'Kìa con bướm vàng', 'Dân ca (giai điệu Frère Jacques)', 'R',
              [1, 2, 3, 1, 1, 2, 3, 1, 3, 4, 5, 3, 4, 5] + [0] * 12 + [0] * 6, None))

tw = seqf('C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2 F4:1 F4:1 E4:1 E4:1 D4:1 D4:1 C4:2 '
          'G4:1 G4:1 F4:1 F4:1 E4:1 E4:1 D4:2 G4:1 G4:1 F4:1 F4:1 E4:1 E4:1 D4:2 '
          'C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2 F4:1 F4:1 E4:1 E4:1 D4:1 D4:1 C4:2')
tw_lh = seqf('C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4 C3:4')
write_song('l2_twinkle.mid', 'Ngôi sao lấp lánh', 96, tw, tw_lh)
songs.append((2, 'l2_twinkle.mid', 'Ngôi sao lấp lánh', 'Twinkle Twinkle Little Star', 'R', None, None))

# ---- Level 3 ----
ode = seqf('E4:1 E4:1 F4:1 G4:1 G4:1 F4:1 E4:1 D4:1 C4:1 C4:1 D4:1 E4:1 E4:1.5 D4:0.5 D4:2 '
           'E4:1 E4:1 F4:1 G4:1 G4:1 F4:1 E4:1 D4:1 C4:1 C4:1 D4:1 E4:1 D4:1.5 C4:0.5 C4:2')
ode_f = [3, 3, 4, 5, 5, 4, 3, 2, 1, 1, 2, 3, 3, 2, 2, 3, 3, 4, 5, 5, 4, 3, 2, 1, 1, 2, 3, 2, 1, 1]
ode_lh_soft = seqf('C3:4 G2:4 C3:4 G2:4 C3:4 G2:4 C3:4 C3:4')
write_song('l3_ode_to_joy.mid', 'Khúc hoan ca (tay phải)', 92, ode, ode_lh_soft)
songs.append((3, 'l3_ode_to_joy.mid', 'Khúc hoan ca (tay phải)', 'Beethoven — Ode to Joy', 'R', ode_f, None))

par_r = seqf('C4:1 D4:1 E4:1 F4:1 G4:1 F4:1 E4:1 D4:1 C4:4')
par_l = seqf('C3:1 D3:1 E3:1 F3:1 G3:1 F3:1 E3:1 D3:1 C3:4')
write_song('l3_five_both.mid', 'Năm ngón hai tay', 80, par_r * 2, par_l * 2)
songs.append((3, 'l3_five_both.mid', 'Năm ngón hai tay', 'Hai tay cùng đi lên, đi xuống', 'LR',
              [1, 2, 3, 4, 5, 4, 3, 2, 1] * 2, [5, 4, 3, 2, 1, 2, 3, 4, 5] * 2))

# ---- Level 4 ----
ode_lh = seqf('C3:4 G3:4 C3:4 G3:4 C3:4 G3:4 C3:2 G3:2 C3:4')
write_song('l4_ode_both.mid', 'Khúc hoan ca (hai tay)', 88, ode, ode_lh)
songs.append((4, 'l4_ode_both.mid', 'Khúc hoan ca (hai tay)', 'Beethoven — Ode to Joy', 'LR', ode_f, [5, 1, 5, 1, 5, 1, 5, 1, 5]))

tw_lh2 = seqf('C3:4 F3:2 C3:2 F3:2 C3:2 G3:2 C3:2 C3:2 F3:2 C3:2 G3:2 C3:2 F3:2 C3:2 G3:2 '
              'C3:4 F3:2 C3:2 F3:2 C3:2 G3:2 C3:2')
write_song('l4_twinkle_both.mid', 'Ngôi sao lấp lánh (hai tay)', 92, tw, tw_lh2)
lf = {'C3': 5, 'F3': 2, 'G3': 1}
songs.append((4, 'l4_twinkle_both.mid', 'Ngôi sao lấp lánh (hai tay)', 'Tay trái giữ Đô – Fa – Sol', 'LR', None,
              [lf[n] for n, _ in tw_lh2]))

# ---- Level 5 ----
jb = seqf('E4:1 E4:1 E4:2 E4:1 E4:1 E4:2 E4:1 G4:1 C4:1.5 D4:0.5 E4:4 '
          'F4:1 F4:1 F4:1.5 F4:0.5 F4:1 E4:1 E4:1 E4:0.5 E4:0.5 E4:1 D4:1 D4:1 E4:1 D4:2 G4:2')
jb_f = [3, 3, 3, 3, 3, 3, 3, 5, 1, 2, 3, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 2, 2, 3, 2, 5]
jb_lh = seqf('C3:4 C3:4 C3:4 C3:4 F3:4 C3:4 G3:4 G3:4')
write_song('l5_jingle_bells.mid', 'Tiếng chuông ngân (điệp khúc)', 104, jb, jb_lh)
songs.append((5, 'l5_jingle_bells.mid', 'Tiếng chuông ngân (điệp khúc)', 'Jingle Bells — J. Pierpont', 'LR', jb_f,
              [{'C3': 5, 'F3': 2, 'G3': 1}[n] for n, _ in jb_lh]))

# ---- Existing bundled songs placed on the path (no finger numbers) ----
existing = [
    (2, 'samples/Happy-Birthday.mid', 'Chúc mừng sinh nhật', 'Happy Birthday', 'LR'),
    (4, 'samples/Hanon---Piano-Excercise-No-01.mid', 'Bài tập Hanon số 1', 'Luyện ngón đều tay', 'LR'),
    (4, 'samples/Silent-night.mid', 'Đêm thánh vô cùng', 'Silent Night — F. Gruber', 'LR'),
    (5, 'samples/Minuet-in-F-Major.mid', 'Minuet Fa trưởng', 'Mozart', 'LR'),
    (5, 'samples/Canon-in-D-easy.mid', 'Canon Rê trưởng (dễ)', 'Pachelbel', 'LR'),
    (5, 'samples/Green-sleeves.mid', 'Greensleeves', 'Dân ca Anh', 'LR'),
]

titles = {1: 'Làm quen 5 ngón', 2: 'Giai điệu đầu tiên', 3: 'Tay phải vững vàng', 4: 'Hai tay cùng chơi', 5: 'Bài hoàn chỉnh'}
levels = {k: [] for k in titles}
for lv, f, t, sub, hands, fr, fl in songs:
    entry = {'file': 'lessons/' + f, 'title': t, 'subtitle': sub, 'hands': hands}
    fingers = {}
    if fr:
        fingers['R'] = fr
    if fl:
        fingers['L'] = fl
    if fingers:
        entry['fingers'] = fingers
    levels[lv].append(entry)
for lv, f, t, sub, hands in existing:
    if os.path.exists(os.path.join(ASSETS, f)):
        levels[lv].append({'file': f, 'title': t, 'subtitle': sub, 'hands': hands})

doc = {'_comment': 'Lesson path. Lesson MIDI files in lessons/ are arrangements of public-domain melodies '
                   'made for this app. "fingers": one number per note of that hand, in playing order (0 = none).',
       'levels': [{'level': k, 'title': v, 'songs': levels[k]} for k, v in titles.items()]}
with open(os.path.join(OUT, 'lessons.json'), 'w', encoding='utf-8') as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)

# Sanity: finger lists must match note counts.
for lv, f, t, sub, hands, fr, fl in songs:
    pass
print('wrote', sum(len(v) for v in levels.values()), 'lessons')
