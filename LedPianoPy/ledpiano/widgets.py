"""Custom-drawn views: falling notes, keyboard, staff, loop range bar, week bars."""
from __future__ import annotations

import datetime as dt

from PySide6.QtCore import QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QFont, QPainter, QPen
from PySide6.QtWidgets import QWidget

from .midi import LEFT, PIANO_HIGHEST, PIANO_LOWEST, RIGHT, key_label, note_name

BLACK_PC = {1, 3, 6, 8, 10}
INK, SURF1, SURF2, TEXT_HI, TEXT_MID = "#101218", "#1A1D26", "#242833", "#F2F0EA", "#A6ADBB"


def is_black(p):
    return p % 12 in BLACK_PC


def white_before(p):
    return sum(1 for q in range(0, p) if not is_black(q))


class KeyGeom:
    """Shared key -> x math so roll, keyboard and staff line up."""

    def __init__(self, low=PIANO_LOWEST, high=PIANO_HIGHEST):
        self.low, self.high = low, high
        self._wb = [white_before(p) for p in range(130)]

    def white_count(self):
        return self._wb[self.high + 1] - self._wb[self.low]

    def x(self, p, w):
        ww = w / max(1, self.white_count())
        base = (self._wb[p] - self._wb[self.low]) * ww
        if is_black(p):
            return base - 0.31 * ww, 0.62 * ww
        return base, ww


def with_alpha(hexcol: str, a: float) -> QColor:
    c = QColor(hexcol)
    c.setAlphaF(max(0.0, min(1.0, a)))
    return c


class PianoRoll(QWidget):
    scrub = Signal(float)
    tapped = Signal()

    def __init__(self, geom: KeyGeom):
        super().__init__()
        self.geom = geom
        self.song = None
        self.pos = 0.0
        self.px_per_sec = 190.0
        self.show_left = self.show_right = True
        self.colors = {LEFT: "#3B82F6", RIGHT: "#F97316"}
        self.guidance = 1.0
        self.label_mode = "none"
        self.fingers: dict[int, int] = {}
        self.loop = (None, None)
        self.measures: list[float] = []
        self._drag_y = None
        self._moved = False
        self.setMinimumHeight(80)

    def set_song(self, s):
        self.song = s
        self.measures = s.measure_secs() if s else []
        self.update()

    def practised(self, n):
        return not (n.hand == LEFT and not self.show_left) and not (n.hand == RIGHT and not self.show_right)

    def paintEvent(self, _e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        p.fillRect(self.rect(), QColor("#0B0D12"))
        now_y = h - 8
        t_top = self.pos + now_y / self.px_per_sec
        t_bot = self.pos - (h - now_y) / self.px_per_sec
        y_of = lambda t: now_y - (t - self.pos) * self.px_per_sec
        p.setPen(QPen(QColor("#2A313B"), 1))
        for m in self.measures:
            if t_bot <= m <= t_top:
                p.drawLine(QPointF(0, y_of(m)), QPointF(w, y_of(m)))
        a, b = self.loop
        if a is not None and b is not None:
            p.fillRect(QRectF(0, y_of(b), w, y_of(a) - y_of(b)), with_alpha(TEXT_HI, 0.1))
        if self.song:
            f = QFont()
            f.setBold(True)
            f.setPixelSize(11)
            p.setFont(f)
            for n in self.song.notes:
                if n.pitch < self.geom.low or n.pitch > self.geom.high or n.end < t_bot or n.start > t_top:
                    continue
                x, kw = self.geom.x(n.pitch, w)
                r = QRectF(x + 1.5, y_of(n.end), kw - 3, y_of(n.start) - y_of(n.end))
                pr = self.practised(n)
                sounding = n.start <= self.pos < n.end
                alpha = (1.0 if sounding else 0.8)
                if not pr:
                    alpha = 0.25
                elif self.guidance < 1:
                    alpha *= 0.08 + 0.92 * self.guidance
                p.setPen(Qt.NoPen)
                p.setBrush(with_alpha(self.colors.get(n.hand, "#9AA3AF"), alpha))
                p.drawRoundedRect(r, 5, 5)
                if pr and self.guidance > 0.3 and r.height() > 18 and r.width() > 10:
                    lab = self.fingers.get(id(n))
                    lab = str(lab) if lab else (note_name(n.pitch, self.label_mode) if self.label_mode != "c" else None)
                    if lab:
                        p.setPen(QColor(INK))
                        p.drawText(QRectF(r.left(), r.bottom() - 16, r.width(), 14), Qt.AlignCenter, lab)
        p.setPen(QPen(with_alpha(TEXT_HI, 0.6), 2))
        p.drawLine(QPointF(0, now_y), QPointF(w, now_y))

    def wheelEvent(self, e):
        self.px_per_sec = max(60, min(900, self.px_per_sec * (1.1 if e.angleDelta().y() > 0 else 0.9)))
        self.update()

    def mousePressEvent(self, e):
        self._drag_y, self._moved = e.position().y(), False

    def mouseMoveEvent(self, e):
        if self._drag_y is None or not self.song:
            return
        dy = e.position().y() - self._drag_y
        if abs(dy) > 3:
            self._moved = True
            self._drag_y = e.position().y()
            self.scrub.emit(max(0.0, min(self.song.duration, self.pos + dy / self.px_per_sec)))

    def mouseReleaseEvent(self, _e):
        if not self._moved:
            self.tapped.emit()
        self._drag_y = None


class Keyboard(QWidget):
    def __init__(self, geom: KeyGeom):
        super().__init__()
        self.geom = geom
        self.active: dict[int, str] = {}
        self.upcoming: dict[int, str] = {}
        self.pressed: dict[int, bool] = {}
        self.fingers: dict[int, int] = {}
        self.colors = {LEFT: "#3B82F6", RIGHT: "#F97316"}
        self.show_left = self.show_right = True
        self.guidance = 1.0
        self.label_mode = "c"
        self.setMinimumHeight(90)

    def _hl(self, p, pitch, r):
        hand = self.active.get(pitch) or self.upcoming.get(pitch)
        if not hand or self.guidance <= 0:
            return
        if (hand == LEFT and not self.show_left) or (hand == RIGHT and not self.show_right):
            return
        act = pitch in self.active
        p.setPen(Qt.NoPen)
        p.setBrush(with_alpha(self.colors.get(hand, "#9AA3AF"), (0.82 if act else 0.41) * self.guidance))
        p.drawRoundedRect(r if act else r.adjusted(3, 3, -3, -3), 3, 3)

    def _press(self, p, pitch, r):
        ok = self.pressed.get(pitch)
        if ok is None:
            return
        p.setPen(Qt.NoPen)
        p.setBrush(QColor("#E622C55E" if ok else "#E6EF4444"))
        bh = min(r.height() * 0.22, 18)
        p.drawRoundedRect(QRectF(r.left() + 2, r.bottom() - bh - 2, r.width() - 4, bh), 3, 3)

    def _finger(self, p, pitch, cx, cy, kw):
        f = self.fingers.get(pitch)
        if not f or self.guidance <= 0:
            return
        rad = min(9, kw * 0.42)
        if rad < 5:
            return
        p.setPen(Qt.NoPen)
        p.setBrush(with_alpha(INK, 0.9 * self.guidance))
        p.drawEllipse(QPointF(cx, cy), rad, rad)
        p.setPen(with_alpha("#FFFFFF", self.guidance))
        p.drawText(QRectF(cx - rad, cy - rad, 2 * rad, 2 * rad), Qt.AlignCenter, str(f))

    def paintEvent(self, _e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        p.fillRect(self.rect(), QColor("#0A0B0F"))
        f = QFont()
        f.setBold(True)
        g = self.geom
        ww = w / max(1, g.white_count())
        f.setPixelSize(int(min(11, ww * 0.42)) or 1)
        p.setFont(f)
        for pitch in range(g.low, g.high + 1):
            if is_black(pitch):
                continue
            x, kw = g.x(pitch, w)
            r = QRectF(x + 0.5, 0, kw - 1, h)
            p.setPen(QPen(QColor("#24262B"), 1))
            p.setBrush(QColor("#F4F2EE"))
            p.drawRoundedRect(r, 4, 4)
            self._hl(p, pitch, r)
            self._press(p, pitch, r)
            lab = key_label(pitch, self.label_mode) if ww >= 14 else None
            if lab:
                p.setPen(QColor("#6B7280"))
                p.drawText(QRectF(r.left(), h - 22, r.width(), 16), Qt.AlignCenter, lab)
            self._finger(p, pitch, r.center().x(), h * 0.68, kw)
        for pitch in range(g.low, g.high + 1):
            if not is_black(pitch):
                continue
            x, kw = g.x(pitch, w)
            r = QRectF(x, 0, kw, h * 0.62)
            p.setPen(Qt.NoPen)
            p.setBrush(QColor("#17181C"))
            p.drawRoundedRect(r, 4, 4)
            self._hl(p, pitch, r)
            self._press(p, pitch, r)
            self._finger(p, pitch, r.center().x(), h * 0.62 * 0.62, kw)


class Staff(QWidget):
    LETTER = [0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6]

    def __init__(self):
        super().__init__()
        self.song = None
        self.pos = 0.0
        self.measures = []
        self.colors = {LEFT: "#3B82F6", RIGHT: "#F97316"}
        self.show_left = self.show_right = True
        self.guidance = 1.0
        self.setFixedHeight(110)

    def set_song(self, s):
        self.song, self.measures = s, (s.measure_secs() if s else [])
        self.update()

    def paintEvent(self, _e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        p.fillRect(self.rect(), QColor("#14171F"))
        gap = h / 15
        half = gap / 2
        y = lambda step: h / 2 - (step - 28) * half
        p.setPen(QPen(QColor("#6B7280"), 1))
        for s in (30, 32, 34, 36, 38, 18, 20, 22, 24, 26):
            p.drawLine(QPointF(0, y(s)), QPointF(w, y(s)))
        p.setPen(QColor(TEXT_MID))
        p.drawText(QPointF(4, y(34) - 2), "Sol")
        p.drawText(QPointF(4, y(22) - 2), "Fa")
        pps = 110
        now_x = w * 0.18
        tl, tr = self.pos - now_x / pps, self.pos + (w - now_x) / pps
        p.setPen(QPen(QColor("#3E4452"), 1))
        for m in self.measures:
            if tl <= m <= tr:
                x = now_x + (m - self.pos) * pps
                p.drawLine(QPointF(x, y(38)), QPointF(x, y(18)))
        if self.song:
            for n in self.song.notes:
                if n.end < tl or n.start > tr:
                    continue
                pr = not (n.hand == LEFT and not self.show_left) and not (n.hand == RIGHT and not self.show_right)
                a = 0.27 if not pr else 0.15 + 0.85 * self.guidance
                step = (n.pitch // 12 - 1) * 7 + self.LETTER[n.pitch % 12]
                yy = y(step)
                x0 = now_x + (n.start - self.pos) * pps
                x1 = now_x + (n.end - self.pos) * pps
                col = self.colors.get(n.hand, "#9AA3AF")
                p.setPen(QPen(QColor("#6B7280"), 1))
                if step == 28:
                    p.drawLine(QPointF(x0 - gap, y(28)), QPointF(x0 + gap, y(28)))
                for s in range(40, step + 1, 2):
                    p.drawLine(QPointF(x0 - gap, y(s)), QPointF(x0 + gap, y(s)))
                for s in range(16, step - 1, -2):
                    p.drawLine(QPointF(x0 - gap, y(s)), QPointF(x0 + gap, y(s)))
                p.setPen(QPen(with_alpha(col, a / 2), 3))
                p.drawLine(QPointF(x0 + gap * 0.6, yy), QPointF(max(x0 + gap * 0.6, x1), yy))
                p.setPen(Qt.NoPen)
                p.setBrush(with_alpha(col, a))
                p.drawEllipse(QRectF(x0 - gap * 0.65, yy - half * 0.95, gap * 1.3, half * 1.9))
                if is_black(n.pitch):
                    p.setPen(QColor(TEXT_HI))
                    p.drawText(QPointF(x0 - gap * 1.8, yy + half), "♯")
        p.setPen(QPen(with_alpha(TEXT_HI, 0.6), 2))
        p.drawLine(QPointF(now_x, 0), QPointF(now_x, h))


class LoopBar(QWidget):
    changed = Signal(float, float)

    def __init__(self):
        super().__init__()
        self.duration, self.snaps, self.a, self.b, self._drag = 1.0, [], 0.0, 1.0, 0
        self.hot: list[tuple[float, float]] = []  # bars with mistakes ("chỗ khó"), shown in red
        self.setFixedHeight(30)

    def set_song(self, dur, measures):
        self.duration = max(dur, 0.1)
        self.snaps = sorted(set(measures + [dur]))

    def set_range(self, a, b):
        self.a, self.b = a, b
        self.update()

    def _x(self, s):
        return 12 + s / self.duration * (self.width() - 24)

    def _s(self, x):
        return max(0.0, min(1.0, (x - 12) / max(1, self.width() - 24))) * self.duration

    def paintEvent(self, _e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        cy = self.height() / 2
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(SURF2))
        p.drawRoundedRect(QRectF(12, cy - 3, self.width() - 24, 6), 3, 3)
        p.setBrush(QColor(255, 90, 90, 170))
        for a, b in self.hot:
            p.drawRect(QRectF(self._x(a), cy - 3, max(2.0, self._x(b) - self._x(a)), 6))
        p.setBrush(with_alpha(TEXT_HI, 0.4))
        p.drawRoundedRect(QRectF(self._x(self.a), cy - 5, self._x(self.b) - self._x(self.a), 10), 3, 3)
        p.setBrush(QColor(TEXT_HI))
        for s in (self.a, self.b):
            p.drawEllipse(QPointF(self._x(s), cy), 8, 8)

    def mousePressEvent(self, e):
        x = e.position().x()
        self._drag = 1 if abs(x - self._x(self.a)) <= abs(x - self._x(self.b)) else 2
        self.mouseMoveEvent(e)

    def mouseMoveEvent(self, e):
        if not self._drag or not self.snaps:
            return
        s = min(self.snaps, key=lambda v: abs(v - self._s(e.position().x())))
        if self._drag == 1 and s < self.b:
            self.a = s
        elif self._drag == 2 and s > self.a:
            self.b = s
        self.update()

    def mouseReleaseEvent(self, _e):
        if self._drag:
            self._drag = 0
            self.changed.emit(self.a, self.b)


class WeekBars(QWidget):
    def __init__(self):
        super().__init__()
        self.secs, self.goal = [0] * 7, 600
        self.setFixedSize(126, 44)

    def set_data(self, secs, goal_min):
        self.secs, self.goal = list(secs), goal_min * 60
        self.setToolTip(", ".join(f"{s // 60} phút" for s in self.secs))
        self.update()

    def paintEvent(self, _e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        w, h = self.width(), self.height()
        slot, bw, mh = w / 7, w / 7 * 0.55, h - 13
        today = dt.date.today()
        f = QFont()
        f.setPixelSize(9)
        p.setFont(f)
        for i in range(7):
            cx = slot * i + slot / 2
            frac = min(1.0, self.secs[i] / max(60, self.goal))
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(SURF2))
            p.drawRoundedRect(QRectF(cx - bw / 2, 0, bw, mh), 2.5, 2.5)
            if frac > 0:
                p.setBrush(QColor("#34D399" if frac >= 1 else TEXT_HI))
                p.drawRoundedRect(QRectF(cx - bw / 2, mh * (1 - frac), bw, mh * frac), 2.5, 2.5)
            d = (today - dt.timedelta(days=6 - i)).isoweekday()
            p.setPen(QColor(TEXT_MID))
            p.drawText(QRectF(cx - 12, h - 11, 24, 11), Qt.AlignCenter, "CN" if d == 7 else f"T{d + 1}")
