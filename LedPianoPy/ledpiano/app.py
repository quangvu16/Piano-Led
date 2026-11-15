"""LED Piano — desktop app (Windows / macOS / Linux). Same features as the Android app.

The window adapts like the phone app turning: tall window = portrait layout
(controls under the keyboard), wide window = landscape (controls on the right).
"""
from __future__ import annotations

import bisect
import os
import sys
import threading
import time

from PySide6.QtCore import QObject, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QFont, QKeySequence, QShortcut
from PySide6.QtWidgets import (QApplication, QBoxLayout, QCheckBox, QComboBox, QDialog,
                               QDialogButtonBox, QFileDialog, QFormLayout, QFrame, QGridLayout,
                               QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QMainWindow,
                               QMenu, QMessageBox, QPushButton, QScrollArea, QSlider, QSpinBox,
                               QStackedWidget, QVBoxLayout, QWidget)

from . import midi as M
from .engine import MAX_SPEED, MIN_SPEED, Engine, Listener
from .inputs import MIC, MIDI, NONE, InputHub, MicInput, MidiInput
from .led import LedController
from .practice import HIT, SETTINGS_DEFAULT, WRONG, Coach, JsonStore, Lessons, ScoreKeeper, Stats, data_dir
from .soundbank import SoundBank
from .synth import Synth
from . import voice as V
from . import learning as LRN
from . import ws2812 as WS
from . import wled as WL
from .widgets import INK, SURF1, SURF2, TEXT_HI, TEXT_MID, KeyGeom, Keyboard, LoopBar, PianoRoll, Staff, WeekBars

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "assets")

QSS = f"""
* {{ font-family: 'Nunito', 'Segoe UI', 'Helvetica Neue', sans-serif; color: {TEXT_HI}; }}
QMainWindow, QWidget#page, QDialog {{ background: {INK}; }}
QLabel#h1 {{ font-size: 26px; font-weight: 800; }}
QLabel#h2 {{ font-size: 18px; font-weight: 800; }}
QLabel#mid {{ color: {TEXT_MID}; font-size: 13px; }}
QLabel#overlay {{ font-size: 22px; font-weight: 800; background: rgba(16,18,24,170); border-radius: 12px; padding: 10px 16px; }}
QLabel#chip {{ background: {SURF2}; border-radius: 11px; padding: 4px 10px; font-size: 12px; font-weight: 700; }}
QFrame#card {{ background: {SURF1}; border-radius: 18px; }}
QPushButton {{ background: {SURF2}; border: none; border-radius: 22px; min-height: 44px; padding: 0 16px; font-size: 14px; font-weight: 700; }}
QPushButton:hover {{ background: #2E3340; }}
QPushButton#primary {{ background: {TEXT_HI}; color: {INK}; }}
QPushButton#seg {{ background: transparent; border-radius: 10px; min-height: 40px; padding: 0 4px; color: #C9CED8; }}
QPushButton#seg:checked {{ background: {TEXT_HI}; color: {INK}; }}
QPushButton#play {{ background: {TEXT_HI}; color: {INK}; border-radius: 36px; min-width: 72px; min-height: 72px; font-size: 26px; }}
QPushButton#round {{ border-radius: 26px; min-width: 52px; min-height: 52px; font-size: 18px; }}
QPushButton#round:checked {{ background: {TEXT_HI}; color: {INK}; }}
QPushButton#tile {{ background: {SURF1}; border-radius: 18px; min-height: 64px; text-align: left; padding: 10px 16px; font-size: 15px; }}
QFrame#panel {{ background: {SURF1}; }}
QFrame#track {{ background: {INK}; border-radius: 14px; }}
QListWidget {{ background: transparent; border: none; font-size: 15px; }}
QListWidget::item {{ background: {SURF1}; border-radius: 14px; padding: 12px; margin-bottom: 8px; }}
QListWidget::item:selected {{ background: {SURF2}; }}
QSlider::groove:horizontal {{ height: 6px; background: {SURF2}; border-radius: 3px; }}
QSlider::sub-page:horizontal {{ background: {TEXT_HI}; border-radius: 3px; }}
QSlider::handle:horizontal {{ background: {TEXT_HI}; width: 16px; margin: -5px 0; border-radius: 8px; }}
QCheckBox {{ min-height: 40px; font-size: 14px; }}
QComboBox, QSpinBox {{ background: {SURF2}; border-radius: 8px; padding: 6px; min-height: 30px; }}
"""


class Poster(QObject):
    """Runs callables on the Qt main thread (engine / BLE / MIDI threads post here)."""
    sig = Signal(object)

    def __init__(self):
        super().__init__()
        self.sig.connect(lambda fn: fn(), Qt.QueuedConnection)

    def __call__(self, fn):
        self.sig.emit(fn)


def label(text, name=None, wrap=False):
    l = QLabel(text)
    if name:
        l.setObjectName(name)
    l.setWordWrap(wrap)
    return l


def button(text, name=None, checkable=False, tip=None):
    b = QPushButton(text)
    if name:
        b.setObjectName(name)
    b.setCheckable(checkable)
    b.setCursor(Qt.PointingHandCursor)
    if tip:
        b.setToolTip(tip)
        b.setAccessibleName(tip)
    return b


def card():
    f = QFrame()
    f.setObjectName("card")
    return f


class Segments(QFrame):
    """Segmented control: one checked button at a time."""

    def __init__(self, items, on_pick):
        super().__init__()
        self.setObjectName("track")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(4, 4, 4, 4)
        lay.setSpacing(4)
        self.buttons = {}
        for key, text in items:
            b = button(text, "seg", checkable=True)
            b.clicked.connect(lambda _=False, k=key: (self.set(k), on_pick(k)))
            lay.addWidget(b)
            self.buttons[key] = b

    def set(self, key):
        for k, b in self.buttons.items():
            b.setChecked(k == key)


class App:
    """Shared services (the Android Application object)."""

    def __init__(self):
        self.post = Poster()
        self.settings = JsonStore("settings.json", SETTINGS_DEFAULT)
        self.stats = Stats()
        self.lessons = Lessons(ASSETS)
        try:
            self.bank = SoundBank(ASSETS)
        except Exception:
            self.bank = None
        self.led = LedController(self.settings)
        self.hub = InputHub()
        self.midi_in = MidiInput(self.hub, self.post)
        self.mic = MicInput(self.hub, self.post)
        self.midi_in.start()
        self.hot = LRN.HotSpots(self.stats.store)
        # A Bluetooth MIDI piano paired with the ESP32 strip: its notes come in through the LED link.
        self.led.piano_listeners.append(
            lambda p, v: self.post(lambda: self.hub.note_on(p, v) if v else self.hub.note_off(p)))
        self.led.listeners.append(lambda *a: self.post(self._esp32_piano))

    ESP32_PIANO = "Đàn (qua ESP32)"

    def _esp32_piano(self):
        on = bool(self.led.esp32_status[0] & 1) and self.led.state == self.led.CONNECTED
        if on and self.hub.source == NONE:
            self.hub.set_state(MIDI, self.ESP32_PIANO)
        elif not on and self.hub.device == self.ESP32_PIANO:
            self.hub.set_state(NONE, None)

    def add_recent(self, key, title):
        r = [e for e in self.settings["recent"] if e[0] != key]
        self.settings["recent"] = [[key, title]] + r[:49]

    def path_for(self, key):
        return os.path.join(ASSETS, key[len("asset://"):]) if key.startswith("asset://") else key


# ---------------------------------------------------------------- dialogs

class BleDialog(QDialog):
    def __init__(self, app: App, parent, on_pick, title="Kết nối với thiết bị"):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setMinimumWidth(360)
        lay = QVBoxLayout(self)
        self.status = label("Đang quét thiết bị…", "mid")
        self.list = QListWidget()
        lay.addWidget(self.status)
        lay.addWidget(self.list)
        bb = QDialogButtonBox(QDialogButtonBox.Cancel)
        bb.rejected.connect(self.reject)
        wb = bb.addButton("WLED (Wi-Fi)…", QDialogButtonBox.ActionRole)
        wb.clicked.connect(lambda: self._wled(on_pick, app))
        ab = bb.addButton("Art-Net SP801E…", QDialogButtonBox.ActionRole)
        ab.clicked.connect(lambda: self._artnet(on_pick, app))
        mb = bb.addButton("Cách khác…", QDialogButtonBox.ActionRole)  # E1.31, DDP, Arduino Adalight
        mb.clicked.connect(lambda: self._more(on_pick, app))
        lay.addWidget(bb)
        self.list.itemDoubleClicked.connect(lambda it: (self.accept(), on_pick(it.data(Qt.UserRole))))
        usb = WS.serial_ports()  # ESP32 + WS2812B on a USB cable
        # LED Piano Strip ESP32 boards and WLED controllers on the same Wi-Fi (broadcast + Bonjour/mDNS).
        def find_wifi():
            found = WL.find_controllers()
            if found:
                app.post(lambda: self._show(found))
        threading.Thread(target=find_wifi, daemon=True).start()
        if not app.led.available:
            if usb:
                self._show(usb)
            else:
                self.status.setText("Cần cài thư viện bleak để dùng Bluetooth")
            return
        app.led.scan(5.0, lambda devs: app.post(lambda: self._show(usb + devs)))

    def _wled(self, on_pick, app):
        """WLED controller on the same Wi-Fi: the app sets every LED itself."""
        from PySide6.QtWidgets import QInputDialog
        last = app.settings["led_address"] or ""
        ip, ok = QInputDialog.getText(self, "WLED (Wi-Fi)",
                                      "Bộ WLED cùng mạng Wi-Fi tự hiện trong danh sách. Nếu không thấy, nhập địa chỉ IP (xem trong ứng dụng WLED):",
                                      text=last[len("wled:"):] if last.startswith("wled:") else "")
        if ok and ip.strip():
            self.accept()
            on_pick(("WLED " + ip.strip(), "wled:" + ip.strip()))

    def _artnet(self, on_pick, app):
        """Art-Net node, e.g. BanlanX SP801E in Art-Net mode (Wi-Fi)."""
        from PySide6.QtWidgets import QInputDialog
        last = app.settings["led_address"] or ""
        ip, ok = QInputDialog.getText(
            self, "Art-Net – BanlanX SP801E",
            "Bật chế độ Art-Net cho SP801E trong ứng dụng BanlanX, rồi nhập IP của nó\n"
            "(nối Wi-Fi “SP801E_…”, mật khẩu 12345678 → thường là 192.168.4.1; thêm “/1” nếu bắt đầu ở universe 1):",
            text=last[len("artnet:"):] if last.startswith("artnet:") else "192.168.4.1")
        if ok and ip.strip():
            self.accept()
            app.settings.data["led_transport"] = "sp801e"  # Art-Net = BanlanX SP801E (any Art-Net node works)
            on_pick(("Art-Net " + ip.strip(), "artnet:" + ip.strip()))

    def _more(self, on_pick, app):
        """E1.31 / sACN, DDP (Wi-Fi pixel controllers), or an Arduino running Adalight (USB / HC-05)."""
        from PySide6.QtWidgets import QInputDialog
        kinds = ["E1.31 / sACN (Wi-Fi) – WLED, ESPixelStick, Falcon…", "DDP (Wi-Fi) – WLED, ESPixelStick, xLights…",
                 "Arduino Uno/Nano – Adalight (cáp USB hoặc Bluetooth HC-05)",
                 "ESP32 LED Piano Strip qua Wi-Fi (nhập IP)"]
        k, ok = QInputDialog.getItem(self, "Cách kết nối khác", "Chọn loại:", kinds, 0, False)
        if not ok:
            return
        i = kinds.index(k)
        if i == 2:
            ports = [d.replace("serial:", "") for _, d in WS.serial_ports(include_bluetooth=True)]
            if not ports:
                self.status.setText("Không thấy cổng nào — cắm Arduino, hoặc ghép đôi HC-05 trong Cài đặt Bluetooth")
                return
            port, ok = QInputDialog.getItem(self, "Arduino (Adalight)", "Cổng của Arduino / HC-05:", ports, 0, False)
            if ok:
                self.accept()
                on_pick(("Arduino " + port, WL.ADA_PREFIX + "serial:" + port))
            return
        prefix, hint, name = [(WL.E131_PREFIX, "IP (thêm “/2” nếu bắt đầu ở universe 2):", "E1.31 "),
                              (WL.DDP_PREFIX, "IP của bộ điều khiển:", "DDP "),
                              (None, None, None), (WL.STRIP_PREFIX, "IP của mạch ESP32 (xem trang http://ledpiano.local):",
                                                   "LED Piano Strip ")][i]
        last = app.settings["led_address"] or ""
        ip, ok = QInputDialog.getText(self, k, hint, text=last[len(prefix):] if last.startswith(prefix) else "")
        if ok and ip.strip():
            self.accept()
            on_pick((name + ip.strip(), prefix + ip.strip()))

    def _show(self, devs):
        n = self.list.count() + len(devs)
        self.status.setText(f"Tìm thấy {n} thiết bị — nhấp đúp để kết nối" if n else "Không tìm thấy thiết bị nào")
        # ESP32-WROOM-32 / ESP32-S3-DevKitC / ESP32-C3 Super Mini boards running LedPianoStrip first, clearly named
        # (the firmware calls itself "LED Piano Strip S3" on an ESP32-S3, "… C3" on an ESP32-C3).
        def esp32(name):
            return (name or "").lower().startswith("led piano")
        for name, addr in sorted(devs, key=lambda d: not esp32(d[0])):
            board = WS.board_name(name)
            label = f"{name}  · {board} · WS2812B\n{addr}" if esp32(name) else f"{name}\n{addr}"
            it = QListWidgetItem(label)
            it.setData(Qt.UserRole, (name, addr))
            self.list.addItem(it)


class SettingsDialog(QDialog):
    def __init__(self, app: App, parent):
        super().__init__(parent)
        self.setWindowTitle("Cài đặt")
        s = app.settings
        form = QFormLayout(self)
        tr = QComboBox()
        tr.addItem("SP611E (BanlanX) – sáng theo vùng", "sp611e")
        tr.addItem("Led Organ – sáng từng phím", "legacy_nus")
        tr.addItem("ESP32 (WROOM-32, S3-DevKitC N16R8 hoặc C3 Super Mini) + WS2812B (Bluetooth, USB, Wi-Fi)", "ws2812")
        tr.addItem("WLED (Wi-Fi) – điều khiển từng đèn qua Wi-Fi", "wled")
        tr.addItem("SP801E (BanlanX, Wi-Fi Art-Net) – điều khiển từng đèn", "sp801e")
        tr.addItem("SP802E (BanlanX, Bluetooth) – cả dải đổi màu theo tay (thử nghiệm)", "sp802e")
        tr.addItem("SP630E (BanlanX, Bluetooth) – cả dải đổi màu theo tay", "sp630e")
        tr.addItem("E1.31 / sACN (Wi-Fi) – điều khiển từng đèn", "e131")
        tr.addItem("DDP (Wi-Fi) – điều khiển từng đèn", "ddp")
        tr.addItem("Arduino Uno/Nano – Adalight (USB / HC-05) – điều khiển từng đèn", "adalight")
        tr.setCurrentIndex(max(0, tr.findData(s["led_transport"])))
        dens = QComboBox()
        for v, t in ((144, "144 LED/mét (khuyên dùng)"), (96, "96 LED/mét"), (74, "74 LED/mét"), (60, "60 LED/mét"), (30, "30 LED/mét (nhiều phím dùng chung 1 đèn)")):
            dens.addItem(t, v)
        dens.setCurrentIndex(max(0, dens.findData(int(s["ws_density"]))))
        keys = QComboBox()
        for v, t in ((88, "88 phím (A0–C8)"), (76, "76 phím (E1–G7)"), (61, "61 phím (C2–C7)"), (49, "49 phím (C2–C6)")):
            keys.addItem(t, v)
        keys.setCurrentIndex(max(0, keys.findData(int(s["ws_keys"]))))
        rev = QCheckBox("Đầu cắm dây (DIN) ở phía phím cao")
        rev.setChecked(bool(s["ws_reversed"]))
        eff = QComboBox()
        for k, t in (("spectrum", "Phổ nốt nhạc"), ("single", "Màu đơn sắc"), ("full", "Phổ nhiều màu")):
            eff.addItem(t, k)
        eff.setCurrentIndex(max(0, eff.findData(s.data.get("led_effect", "spectrum"))))
        colors = [("#3B82F6", "Xanh dương"), ("#EF4444", "Đỏ"), ("#22C55E", "Xanh lá"), ("#F59E0B", "Vàng"),
                  ("#A855F7", "Tím"), ("#06B6D4", "Cyan"), ("#F97316", "Cam")]
        cl, cr = QComboBox(), QComboBox()
        for c in (cl, cr):
            for hx, t in colors:
                c.addItem(t, hx)
        cl.setCurrentIndex(max(0, cl.findData(s["color_left"])))
        cr.setCurrentIndex(max(0, cr.findData(s["color_right"])))
        vol = QSlider(Qt.Horizontal)
        vol.setRange(0, 100)
        vol.setValue(s["volume"])
        goal = QSpinBox()
        goal.setRange(5, 60)
        goal.setValue(s["daily_goal"])
        rec = QCheckBox("Tự kết nối lại đèn LED")
        rec.setChecked(s["auto_reconnect"])
        form.addRow("Giao thức LED", tr)
        form.addRow("Hiệu ứng LED", eff)
        form.addRow("WS2812B: mật độ đèn", dens)
        form.addRow("WS2812B: số phím đàn", keys)
        form.addRow(rev)
        form.addRow("Màu tay trái", cl)
        form.addRow("Màu tay phải", cr)
        form.addRow("Âm lượng", vol)
        form.addRow("Nhạc cụ", InstrumentPicker(app, lambda p: None))
        form.addRow("Mục tiêu mỗi ngày (phút)", goal)
        form.addRow(rec)
        if app.bank is not None:
            credit = label("Âm thanh: " + app.bank.credit, "mid", wrap=True)
            form.addRow(credit)
        bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        form.addRow(bb)

        def save():
            s.data.update(led_transport=tr.currentData(), led_effect=eff.currentData(),
                          color_left=cl.currentData(), color_right=cr.currentData(), volume=vol.value(),
                          daily_goal=goal.value(), auto_reconnect=rec.isChecked(),
                          ws_density=dens.currentData(), ws_keys=keys.currentData(), ws_reversed=rev.isChecked())
            s.save()
            self.accept()
        bb.accepted.connect(save)
        bb.rejected.connect(self.reject)


class InstrumentPicker(QWidget):
    """Grouped list of the recorded instruments (pianos, organs, bells) + a preview button."""

    def __init__(self, app: App, on_pick, synth: Synth | None = None):
        super().__init__()
        self.app, self.on_pick = app, on_pick
        self.synth = synth or Synth(app.bank, app.settings["program"])
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        self.combo = QComboBox()
        if app.bank is None:
            self.combo.addItem("Âm thanh tổng hợp (thiếu gói âm thanh)", 0)
            self.combo.setEnabled(False)
        else:
            for title, items in app.bank.listing():
                self.combo.addItem(f"— {title} —", None)
                self.combo.model().item(self.combo.count() - 1).setEnabled(False)
                for prog, name in items:
                    self.combo.addItem("    " + name, prog)
            self.combo.setCurrentIndex(max(0, self.combo.findData(app.settings["program"])))
        self.combo.currentIndexChanged.connect(self._changed)
        test = button("▶ Nghe thử", tip="Nghe thử nhạc cụ")
        test.clicked.connect(self.preview)
        lay.addWidget(self.combo, 1)
        lay.addWidget(test)

    def _changed(self, _i):
        prog = self.combo.currentData()
        if prog is None:
            return
        self.app.settings["program"] = prog
        self.synth.program = prog
        self.on_pick(prog)
        self.preview()

    def preview(self):
        s = self.synth
        s.start()
        s.program = self.app.settings["program"]
        for i, k in enumerate((60, 64, 67, 72)):
            QTimer.singleShot(i * 220, lambda k=k: s.note_on(k, 96))
            QTimer.singleShot(i * 220 + 600, lambda k=k: s.note_off(k))


class OptionsDialog(QDialog):
    """Tùy chọn luyện tập — everything not needed every minute."""

    HINTS = {
        "normal": "Bài chạy theo nhịp. Nếu nối đàn, ứng dụng chấm điểm từng nốt.",
        "wait": "Bài dừng ở mỗi nốt cho tới khi bạn bấm đúng phím trên đàn (cần nối đàn hoặc bật micro).",
        "step": "Bài dừng ở mỗi nốt; nhấp vào màn hình hoặc bấm phím → / Space / bàn đạp để đi tiếp.",
        "full": "Luôn hiện phím cần bấm.",
        "fade": "Đèn mờ dần khi bạn chơi đúng và sáng lại khi sai, để bạn không phụ thuộc vào đèn.",
        "off": "Bài kiểm tra: tự chơi không cần đèn.",
    }

    def __init__(self, app: App, parent, show_split: bool, on_change):
        super().__init__(parent)
        self.app, self.on_change = app, on_change
        s = app.settings
        self.setWindowTitle("Tùy chọn luyện tập")
        self.setMinimumWidth(420)
        outer = QVBoxLayout(self)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        body = QWidget()
        body.setObjectName("page")
        lay = QVBoxLayout(body)
        scroll.setWidget(body)
        outer.addWidget(scroll)
        lay.addWidget(label("Tùy chọn luyện tập", "h2"))

        def section(t):
            lay.addSpacing(8)
            lay.addWidget(label(t, "mid"))

        section("Âm thanh nhạc cụ")
        lay.addWidget(InstrumentPicker(app, lambda p: on_change("instrument"), getattr(parent, "synth", None)))
        section("Cách luyện")
        self.mode_hint = label("", "mid", wrap=True)
        self.mode = Segments([("normal", "Tự chạy"), ("wait", "Chờ phím"), ("step", "Từng bước")],
                             lambda k: self._set("practice_mode", k, "mode"))
        lay.addWidget(self.mode)
        lay.addWidget(self.mode_hint)
        section("Đèn hướng dẫn")
        self.lights_hint = label("", "mid", wrap=True)
        self.lights = Segments([("full", "Đầy đủ"), ("fade", "Mờ dần"), ("off", "Tắt")],
                               lambda k: self._set("lights_mode", k, "lights"))
        lay.addWidget(self.lights)
        lay.addWidget(self.lights_hint)
        section("Tên nốt")
        self.labels = Segments([("none", "Không"), ("c", "Chỉ Đô"), ("letters", "C D E"), ("solfege", "Đô Rê Mi")],
                               lambda k: self._set("label_mode", k, "labels"))
        lay.addWidget(self.labels)
        section("Khác")
        for key, text in (("count_in", "Đếm nhịp trước khi chơi"), ("metronome", "Máy đếm nhịp"),
                          ("auto_speed", "Tự tăng tốc sau 3 lần lặp đúng"), ("show_staff", "Hiện khuông nhạc"),
                          ("show_fingers", "Hiện số ngón tay")):
            cb = QCheckBox(text)
            cb.setChecked(bool(s[key]))
            cb.toggled.connect(lambda v, k=key: self._set(k, v, k))
            lay.addWidget(cb)
        section("Đèn LED (dải WS2812B, WLED, SP801E)")
        for key, text in (("led_preview", "Nốt kế tiếp sáng mờ trước"),
                          ("led_feedback", "Nháy màu: đỏ = sai phím, xanh = đúng nhịp, cam = sớm/trễ"),
                          ("led_dynamics", "Độ sáng theo độ mạnh/nhẹ của nốt")):
            cb = QCheckBox(text)
            cb.setChecked(bool(s[key]))
            cb.toggled.connect(lambda v, k=key: self._set(k, v, "led"))
            lay.addWidget(cb)
        if getattr(parent, "song", None) is not None:
            sb = button("Gửi bài này vào ESP32 (luyện không cần điện thoại)")
            sb.clicked.connect(lambda: self._send_song(parent))
            lay.addWidget(sb)
        if show_split:
            row = QHBoxLayout()
            row.addWidget(label("Chia tay trái / phải tại"))
            self.split = QSpinBox()
            self.split.setRange(36, 84)
            self.split.setValue(s["hand_split"])
            self.split.setSuffix("")
            self.split_name = label("")
            self.split.valueChanged.connect(lambda v: (self._set("hand_split", v, "split"), self._split_name()))
            row.addWidget(self.split)
            row.addWidget(self.split_name)
            lay.addLayout(row)
            self._split_name()
        section("Đàn của bạn")
        self.input_status = label("")
        lay.addWidget(self.input_status)
        lay.addWidget(label("Cắm đàn điện vào máy tính bằng cáp USB — ứng dụng tự nhận. Đàn Bluetooth MIDI: "
                            "ghép đôi trong cài đặt Bluetooth/MIDI của máy trước.", "mid", wrap=True))
        self.mic = QCheckBox("Nghe qua micro (đàn cơ, từng nốt đơn, thử nghiệm)")
        self.mic.setChecked(s["mic_input"])
        self.mic.toggled.connect(self._mic)
        lay.addWidget(self.mic)
        lay.addStretch(1)
        close = button("Xong", "primary")
        close.clicked.connect(self.accept)
        outer.addWidget(close)
        self.refresh()
        self.update_input()

    def _send_song(self, player):
        led = self.app.led
        if led.state != led.CONNECTED or led.s["led_transport"] != "ws2812":
            QMessageBox.information(self, "ESP32", "Hãy kết nối mạch ESP32 (LED Piano Strip) trước")
            return
        n = led.send_song(player.song)
        QMessageBox.information(self, "ESP32", f"Đã gửi {n} nốt vào thư viện bài của mạch ESP32. Trên mạch: nhấn BOOT để "
                                "luyện bài này (đèn chờ bạn bấm đúng phím trên đàn), nhấn 2 lần nhanh để đổi bài, "
                                "giữ để dừng. Danh sách bài: trang http://ledpiano.local")

    def _split_name(self):
        v = self.split.value()
        self.split_name.setText(f"{M.note_name(v, 'letters')}{M.octave(v)}")

    def _set(self, key, value, what):
        self.app.settings[key] = value
        self.refresh()
        self.on_change(what)

    def _mic(self, on):
        self.app.settings["mic_input"] = on
        if on and not self.app.mic.start():
            self.mic.setChecked(False)
            self.app.settings["mic_input"] = False
            QMessageBox.warning(self, "Micro", "Không mở được micro.")
        elif not on:
            self.app.mic.stop()

    def update_input(self):
        h = self.app.hub
        self.input_status.setText({MIDI: f"Đã nối đàn: {h.device}", MIC: "Đang nghe qua micro"}.get(
            h.source, "Chưa nối đàn"))

    def refresh(self):
        s = self.app.settings
        self.mode.set(s["practice_mode"])
        self.lights.set(s["lights_mode"])
        self.labels.set(s["label_mode"])
        self.mode_hint.setText(self.HINTS[s["practice_mode"]])
        self.lights_hint.setText(self.HINTS[s["lights_mode"]])


# ---------------------------------------------------------------- pages

class VoiceDialog(QDialog):
    """Chọn bài bằng giọng nói: talk to Xiaozhi and it opens a song (FriendBot Voice Control style).

    Paired once at xiaozhi.me; the mic streams opus to Xiaozhi, which answers in its own voice and
    calls the app's MCP tools (list_songs / open_song). Hands-free: listening restarts after each reply.
    """

    STATES = {"connecting": "Đang kết nối Xiaozhi…", "pairing": "Chờ ghép nối…", "ready": "Sẵn sàng",
              "listening": "Đang nghe… mời bạn nói tên bài", "thinking": "Xiaozhi đang nghĩ…",
              "speaking": "Xiaozhi đang nói…", "offline": "Mất kết nối, đang thử lại…"}

    def __init__(self, win):
        super().__init__(win)
        self.win, self.app = win, win.app
        self.setWindowTitle("Chọn bài bằng giọng nói")
        self.resize(460, 680)
        self.ident = V.Identity(self.app.settings)
        self.activator = self.session = self.va = None
        self.state = "connecting"
        self.want_listening = True
        self.tts_active = False
        self.pending = None
        self.last_user = ""
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 12, 16, 16)
        top = QHBoxLayout()
        top.addWidget(label("Chọn bài bằng giọng nói", "h2"), 1)
        self.unpair_btn = button("Huỷ ghép nối")
        self.unpair_btn.clicked.connect(self.unpair)
        top.addWidget(self.unpair_btn)
        lay.addLayout(top)
        self.pair = card()
        pv = QVBoxLayout(self.pair)
        pv.setContentsMargins(16, 14, 16, 14)
        t = label("Ghép nối với Xiaozhi (một lần)")
        t.setStyleSheet("font-weight:700;font-size:16px")
        pv.addWidget(t)
        self.code = label("······")
        self.code.setAlignment(Qt.AlignCenter)
        self.code.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.code.setStyleSheet("font-size:42px;font-weight:800;letter-spacing:8px;color:#7FB0FF")
        pv.addWidget(self.code)
        pv.addWidget(label("1. Mở trang xiaozhi.me và đăng nhập.\n2. Chọn “Thêm thiết bị”.\n"
                           "3. Nhập mã ở trên. Ứng dụng sẽ tự kết nối.", "mid", wrap=True))
        self.pair.setVisible(False)
        lay.addWidget(self.pair)
        self.rows = QListWidget()
        self.rows.setWordWrap(True)
        self.rows.addItem("Thử nói:\n• “Mở bài Ngôi sao lấp lánh”\n• “Có những bài nào?”\n"
                          "• “Chơi bài Chúc mừng sinh nhật luôn nhé”")
        lay.addWidget(self.rows, 1)
        self.state_lbl = label("", None)
        self.state_lbl.setAlignment(Qt.AlignCenter)
        self.state_lbl.setStyleSheet("font-weight:700;font-size:16px")
        lay.addWidget(self.state_lbl)
        self.mic = QPushButton("🎤")
        self.mic.setCursor(Qt.PointingHandCursor)
        self.mic.setAccessibleName("Micro")
        self.mic.setFixedSize(96, 96)
        self.mic.setStyleSheet("QPushButton{background:#0A66D6;border-radius:48px;font-size:36px;color:white}")
        self.mic.clicked.connect(self.mic_tapped)
        mr = QHBoxLayout()
        mr.addStretch(1)
        mr.addWidget(self.mic)
        mr.addStretch(1)
        lay.addLayout(mr)
        self.hint = label("Chạm micro để nói", "mid")
        self.hint.setAlignment(Qt.AlignCenter)
        lay.addWidget(self.hint)
        self.set_state("connecting")
        QTimer.singleShot(0, self.begin)

    # ---- lifecycle
    def begin(self):
        if V._OPUS is None:
            self.add(V.OPUS_HELP, "bot")
            self.set_state("offline")
            return
        saved = self.ident.saved
        if saved:
            self.start_session(*saved)
        else:
            self.start_pairing()

    def end(self):
        if self.activator:
            self.activator.cancel()
        if self.va:
            self.va.stop()
        if self.session:
            self.session.disconnect()
        self.activator = self.session = self.va = None

    def done(self, r):
        self.end()
        super().done(r)

    def closeEvent(self, e):
        self.end()
        super().closeEvent(e)

    def unpair(self):
        self.end()
        self.ident.forget()
        self.code.setText("······")
        self.start_pairing()

    def start_pairing(self):
        self.set_state("pairing")
        self.pair.setVisible(True)
        post = self.app.post
        a = V.Activator(self.ident)
        self.activator = a
        a.start(lambda c: post(lambda: self.code.setText(c or "······")),
                lambda url, tok: post(lambda: self._bound(url, tok)),
                lambda m: post(lambda: (self.add(f"Ghép nối lỗi: {m}", "bot"), self.set_state("offline"))))

    def _bound(self, url, tok):
        self.activator = None
        self.start_session(url, tok)

    def start_session(self, url, tok):
        self.pair.setVisible(False)
        self.set_state("connecting")
        self.session = V.Session(url, tok, self.ident, self)
        self.session.connect()

    # ---- Session listener (session thread → Qt thread)
    def connected(self):
        self.app.post(self._connected)

    def _connected(self):
        if not self.session:
            return
        if self.va:
            self.va.stop()
        try:
            codec = V.OpusCodec(self.session.playback_rate)
            self.va = V.VoiceAudio(codec, self._frame, lambda: self.app.post(self._drained))
        except Exception as e:
            self.add(f"Không mở được micro/loa: {e}", "bot")
            self.set_state("offline")
            return
        self.set_state("ready")
        if self.want_listening:
            self.listen()

    def disconnected(self, reconnecting, auth_failed):
        def ui():
            if self.va:
                self.va.mic_on = False
            if auth_failed:
                self.add("Thiết bị đã bị gỡ khỏi xiaozhi.me. Hãy ghép nối lại.", "bot")
                self.unpair()
            else:
                self.set_state("offline" if reconnecting else "ready")
        self.app.post(ui)

    def stt(self, text):
        def ui():
            if text != self.last_user:
                self.last_user = text
                self.add(text, "user")
                self.set_state("thinking")
        self.app.post(ui)

    def assistant(self, text):
        self.app.post(lambda: self.add(text, "bot"))

    def speaking(self, on):
        def ui():
            self.tts_active = on
            if on:
                self.set_state("speaking")
            elif not (self.va and self.va.busy):
                self.after_speech()
        self.app.post(ui)

    def audio(self, opus):  # Session listener: one opus speech packet
        a = self.va
        if a:
            a.play(opus)

    def tool_list(self):
        return V.TOOLS

    def tool_call(self, name, args):
        # Runs on the session thread; the catalog only reads settings.
        songs = self.catalog()
        return V.call_tool(name, args, songs, lambda s, ap: self.app.post(lambda: self._chosen(s, ap)))

    # ---- helpers
    def _frame(self, pkt, level):
        if not self.speaking_now() and self.session:
            self.session.send_audio(pkt)

    def speaking_now(self):
        a = self.va
        return self.tts_active or bool(a and a.busy)

    def _drained(self):
        if not self.tts_active:
            self.after_speech()

    def catalog(self):
        songs = [{"key": l.key, "title": l.title, "subtitle": l.subtitle} for l in self.app.lessons.all()]
        sd = os.path.join(ASSETS, "samples")
        for f in sorted(os.listdir(sd) if os.path.isdir(sd) else []):
            if f.lower().endswith(".mid"):
                songs.append({"key": "asset://samples/" + f, "title": f[:-4].replace("_", " ").replace("-", " ").strip()})
        for key, title in self.app.settings["recent"]:
            songs.append({"key": key, "title": title})
        seen, out = set(), []
        for s in songs:
            if s["key"] not in seen:
                seen.add(s["key"])
                out.append(s)
        return out

    def _chosen(self, song, autoplay):
        self.pending = (song, autoplay)
        self.add(f"Đang mở “{song['title']}”…", "action")
        QTimer.singleShot(6000, lambda: self.pending and self.open_pending())

    def open_pending(self):
        if not self.pending:
            return
        song, autoplay = self.pending
        self.pending = None
        self.accept()
        self.win.open_song(song["key"], song["title"])
        if autoplay:
            QTimer.singleShot(600, self.win.player.toggle_play)

    def after_speech(self):
        if self.state != "speaking":
            return
        if self.pending:
            self.open_pending()
        elif self.want_listening:
            self.listen()
        else:
            self.set_state("ready")

    def listen(self):
        s, a = self.session, self.va
        if not s or not s.connected or not a:
            return
        a.mic_on = True
        s.listen_start(True)
        self.set_state("listening")

    def mic_tapped(self):
        s, a = self.session, self.va
        if not s:
            if not self.activator:
                self.begin()
            return
        if self.speaking_now():
            if a:
                a.flush()
            s.abort()
            self.tts_active = False
            self.want_listening = True
            self.listen()
        elif a and a.mic_on:
            self.want_listening = False
            a.mic_on = False
            s.listen_stop()
            self.set_state("ready")
        else:
            self.want_listening = True
            self.listen()

    def set_state(self, st):
        self.state = st
        self.state_lbl.setText(self.STATES[st])
        self.hint.setText({"listening": "Chạm để dừng nghe", "speaking": "Chạm để ngắt lời và nói tiếp"}.get(st, "Chạm micro để nói"))
        self.mic.setEnabled(st not in ("connecting", "pairing"))
        self.unpair_btn.setVisible(self.ident.saved is not None)

    def add(self, text, kind):
        prefix = {"user": "Bạn: ", "bot": "Xiaozhi: ", "action": "» "}[kind]
        it = QListWidgetItem(prefix + text)
        if kind == "user":
            it.setTextAlignment(Qt.AlignRight)
        if kind == "action":
            it.setForeground(Qt.cyan)
        self.rows.addItem(it)
        while self.rows.count() > 41:
            self.rows.takeItem(1)
        self.rows.scrollToBottom()


class Page(QWidget):
    def __init__(self):
        super().__init__()
        self.setObjectName("page")


class HomePage(Page):
    def __init__(self, win):
        super().__init__()
        self.win, self.app = win, win.app
        self.box = QBoxLayout(QBoxLayout.TopToBottom, self)
        self.box.setContentsMargins(20, 20, 20, 12)
        self.box.setSpacing(20)
        left = QWidget()
        lv = QVBoxLayout(left)
        lv.setContentsMargins(0, 0, 0, 0)
        lv.setSpacing(12)
        head = QHBoxLayout()
        head.addWidget(label("LED Piano", "h1"), 1)
        st = button("⚙", "round", tip="Cài đặt")
        st.clicked.connect(lambda: SettingsDialog(self.app, self).exec())
        head.addWidget(st)
        lv.addLayout(head)
        # LED card
        led = card()
        lh = QHBoxLayout(led)
        lh.setContentsMargins(16, 12, 12, 12)
        tv = QVBoxLayout()
        self.led_title = label("Chưa kết nối đèn LED")
        self.led_title.setStyleSheet("font-weight:700;font-size:15px")
        self.led_hint = label("Bật dải đèn, rồi nhấn Kết nối", "mid")
        tv.addWidget(self.led_title)
        tv.addWidget(self.led_hint)
        lh.addLayout(tv, 1)
        self.led_btn = button("Kết nối", "primary")
        self.led_btn.clicked.connect(self._led_click)
        lh.addWidget(self.led_btn)
        lv.addWidget(led)
        row = QHBoxLayout()
        t = button("Thử đèn")
        t.clicked.connect(self._test)
        a = button("Căn đèn với phím")
        a.clicked.connect(lambda: win.show_page(win.wizard))
        row.addWidget(t)
        row.addWidget(a)
        lv.addLayout(row)
        # Progress
        pc = card()
        pv = QVBoxLayout(pc)
        pv.setContentsMargins(14, 14, 14, 14)
        pr = QHBoxLayout()
        sv = QVBoxLayout()
        self.streak = label("")
        self.streak.setStyleSheet("font-weight:700;font-size:15px")
        self.today = label("", "mid")
        sv.addWidget(self.streak)
        sv.addWidget(self.today)
        pr.addLayout(sv, 1)
        self.week = WeekBars()
        pr.addWidget(self.week)
        pv.addLayout(pr)
        self.cont = button("", "tile")
        self.cont.clicked.connect(self._continue)
        pv.addWidget(self.cont)
        lv.addWidget(pc)
        tiles = QHBoxLayout()
        add = button("⤒  Thêm bài hát\nMở tệp MIDI", "tile")
        add.clicked.connect(self._add)
        les = button("✋  Bài học\nLộ trình 5 cấp độ", "tile")
        les.clicked.connect(lambda: win.show_page(win.lessons))
        tiles.addWidget(add)
        tiles.addWidget(les)
        lv.addLayout(tiles)
        self.plan = card()
        self.plan_lay = QVBoxLayout(self.plan)
        self.plan_lay.setContentsMargins(16, 12, 16, 12)
        lv.addWidget(self.plan)
        vb = button("🎤  Nói tên bài\nXiaozhi mở bài cho bạn", "tile")
        vb.clicked.connect(lambda: VoiceDialog(win).exec())
        lv.addWidget(vb)
        lv.addStretch(1)
        self.left = left
        right = QWidget()
        rv = QVBoxLayout(right)
        rv.setContentsMargins(0, 0, 0, 0)
        hr = QHBoxLayout()
        hr.addWidget(label("Bài hát của bạn", "h2"), 1)
        hr.addWidget(label("Nhấp đúp để luyện tập · chuột phải để xóa", "mid"))
        rv.addLayout(hr)
        self.songs = QListWidget()
        self.songs.itemDoubleClicked.connect(lambda it: win.open_song(*it.data(Qt.UserRole)))
        self.songs.setContextMenuPolicy(Qt.CustomContextMenu)
        self.songs.customContextMenuRequested.connect(self._menu)
        rv.addWidget(self.songs, 1)
        self.empty = label("Chưa có bài hát nào.\nNhấn \"Thêm bài hát\" để mở tệp .mid", "mid")
        self.empty.setAlignment(Qt.AlignCenter)
        rv.addWidget(self.empty)
        self.box.addWidget(left)
        self.box.addWidget(right, 1)
        self.app.led.listeners.append(lambda *a: self.app.post(self.refresh_led))

    def resizeEvent(self, e):
        land = self.width() > self.height() * 1.1
        self.box.setDirection(QBoxLayout.LeftToRight if land else QBoxLayout.TopToBottom)
        if land:
            self.left.setFixedWidth(340)
        else:
            self.left.setMinimumWidth(0)
            self.left.setMaximumWidth(16777215)
        super().resizeEvent(e)

    def refresh(self):
        s, st = self.app.settings, self.app.stats
        n = st.streak()
        self.streak.setText(f"{n} ngày liên tiếp" if n else "Bắt đầu chuỗi ngày luyện tập hôm nay")
        self.today.setText(f"Hôm nay: {st.today_seconds() // 60} / {s['daily_goal']} phút")
        self.week.set_data(st.last_seven(), s["daily_goal"])
        self._plan()
        rec = s["recent"]
        self.cont.setVisible(bool(rec))
        if rec:
            self.cont.setText(f"Tiếp tục bài\n{rec[0][1]}   ▶")
        self.songs.clear()
        for key, title in rec:
            kind = "Bài học / bài mẫu" if key.startswith("asset://") else "Tệp MIDI"
            it = QListWidgetItem(f"♪  {title}\n     {kind}")
            it.setData(Qt.UserRole, (key, title))
            self.songs.addItem(it)
        self.empty.setVisible(not rec)
        self.songs.setVisible(bool(rec))
        self.refresh_led()

    def refresh_led(self):
        led = self.app.led
        if led.state == led.CONNECTED:
            self.led_title.setText("Đã kết nối đèn LED")
            self.led_hint.setText(f"{led.device_name} · Phím sẽ sáng khi bạn chơi")
            self.led_btn.setText("Ngắt")
        elif led.state == led.CONNECTING:
            self.led_title.setText("Đang kết nối…")
            self.led_hint.setText("Vui lòng đợi…")
        else:
            self.led_title.setText("Chưa kết nối đèn LED")
            self.led_hint.setText("Bật dải đèn, rồi nhấn Kết nối")
            self.led_btn.setText("Kết nối")

    def _led_click(self):
        led = self.app.led
        if led.state == led.CONNECTED:
            led.disconnect()
        else:
            BleDialog(self.app, self, lambda d: led.connect(d[1], d[0])).exec()

    def _test(self):
        if self.app.led.state == self.app.led.CONNECTED:
            self.app.led.test_lights()
        else:
            QMessageBox.information(self, "LED", "Hãy kết nối đèn LED trước")

    def _add(self):
        path, _ = QFileDialog.getOpenFileName(self, "Mở bài hát", "",
                                              "MIDI hoặc MusicXML (*.mid *.midi *.musicxml *.mxl *.xml);;Tất cả (*)")
        if path:
            self.win.open_song(path, os.path.splitext(os.path.basename(path))[0])

    def _plan(self):
        """Kế hoạch hôm nay: warm-up, review an older song, one new lesson (5–10 minutes)."""
        while self.plan_lay.count():
            w = self.plan_lay.takeAt(0).widget()
            if w:
                w.deleteLater()
        t = label("Kế hoạch hôm nay · 5–10 phút")
        t.setStyleSheet("font-weight:800;font-size:15px")
        self.plan_lay.addWidget(t)
        items = LRN.daily_plan(self.app.lessons.all(), self.app.stats.store.data["songs"], self.app.stats.today())
        for kind, lesson in items:
            b = button(f"{LRN.PLAN_VI[kind]}:  {lesson.title}   ▶")
            b.clicked.connect(lambda _=False, l=lesson: self.win.open_song(l.key, l.title))
            self.plan_lay.addWidget(b)
        self.plan.setVisible(bool(items))

    def _continue(self):
        if self.app.settings["recent"]:
            self.win.open_song(*self.app.settings["recent"][0])

    def _menu(self, pt):
        it = self.songs.itemAt(pt)
        if not it:
            return
        m = QMenu(self)
        act = m.addAction("Xóa khỏi danh sách")
        if m.exec(self.songs.mapToGlobal(pt)) == act:
            key = it.data(Qt.UserRole)[0]
            self.app.settings["recent"] = [e for e in self.app.settings["recent"] if e[0] != key]
            self.refresh()


class LessonsPage(Page):
    def __init__(self, win):
        super().__init__()
        self.win, self.app = win, win.app
        v = QVBoxLayout(self)
        v.setContentsMargins(16, 12, 16, 12)
        top = QHBoxLayout()
        b = button("‹", "round", tip="Quay lại")
        b.clicked.connect(lambda: win.show_page(win.home))
        top.addWidget(b)
        top.addWidget(label("Bài học", "h2"), 1)
        v.addLayout(top)
        self.list = QListWidget()
        self.list.itemActivated.connect(self._open)
        self.list.itemClicked.connect(self._open)
        v.addWidget(self.list)

    def refresh(self):
        self.list.clear()
        used = set()
        st = self.app.stats
        for num, title, lessons in self.app.lessons.levels:
            done = sum(1 for l in lessons if st.record(l.key).get("done"))
            h = QListWidgetItem(f"Cấp {num} · {title}        {done}/{len(lessons)} xong")
            h.setFlags(Qt.NoItemFlags)
            h.setFont(QFont("", 15, QFont.Bold))
            self.list.addItem(h)
            for l in lessons:
                used.add(l.key)
                rec = st.record(l.key)
                hands = {"R": "Tay phải", "L": "Tay trái"}.get(l.hands, "Hai tay")
                extra = f" · Tốt nhất {rec['best']}%" if rec.get("best", -1) >= 0 else (" · Đã xong" if rec.get("done") else "")
                it = QListWidgetItem(f"{'✓' if rec.get('done') else num}   {l.title}\n      {hands} · {l.subtitle}{extra}")
                it.setData(Qt.UserRole, (l.key, l.title))
                self.list.addItem(it)
        sd = os.path.join(ASSETS, "samples")
        others = sorted(f for f in (os.listdir(sd) if os.path.isdir(sd) else []) if f.lower().endswith(".mid"))
        others = [f for f in others if "asset://samples/" + f not in used]
        if others:
            h = QListWidgetItem("Bài mẫu khác")
            h.setFlags(Qt.NoItemFlags)
            h.setFont(QFont("", 15, QFont.Bold))
            self.list.addItem(h)
            for f in others:
                name = f[:-4].replace("_", " ").replace("-", " ").strip()
                key = "asset://samples/" + f
                done = st.record(key).get("done")
                it = QListWidgetItem(f"{'✓' if done else '♪'}   {name}")
                it.setData(Qt.UserRole, (key, name))
                self.list.addItem(it)

    def _open(self, it):
        d = it.data(Qt.UserRole)
        if d:
            self.win.open_song(*d)


class WizardPage(Page):
    """Căn đèn LED: count LEDs, align middle C, brightness."""

    def __init__(self, win):
        super().__init__()
        self.win, self.app = win, win.app
        led, s = self.app.led, self.app.settings
        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 12, 16, 12)
        top = QHBoxLayout()
        b = button("‹", "round", tip="Quay lại")
        b.clicked.connect(self._back)
        top.addWidget(b)
        top.addWidget(label("Căn đèn LED", "h2"), 1)
        outer.addLayout(top)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        body = Page()
        v = QVBoxLayout(body)
        scroll.setWidget(body)
        outer.addWidget(scroll)

        def step(title, hint):
            c = card()
            cv = QVBoxLayout(c)
            cv.setContentsMargins(16, 16, 16, 16)
            t = label(title)
            t.setStyleSheet("font-weight:800;font-size:17px")
            cv.addWidget(t)
            h = label(hint, "mid", wrap=True)
            cv.addWidget(h)
            v.addWidget(c)
            return cv, t, h

        c0, self.status, self.status_hint = step("", "")
        r = QHBoxLayout()
        self.connect_btn = button("Kết nối", "primary")
        self.connect_btn.clicked.connect(lambda: BleDialog(self.app, self, lambda d: led.connect(d[1], d[0])).exec())
        tl = button("Thử đèn")
        tl.clicked.connect(led.test_lights)
        r.addWidget(self.connect_btn)
        r.addWidget(tl)
        c0.addLayout(r)

        c1, _, _ = step("1. Số đèn trên dải", "Nhấn − / + cho tới khi đèn cuối cùng sáng đúng ở cuối dải.")
        r = QHBoxLayout()
        self.count = label("")
        self.count.setAlignment(Qt.AlignCenter)
        self.count.setStyleSheet("font-size:20px;font-weight:800")
        for t, d in (("−", -1), (None, 0), ("+", 1)):
            if t is None:
                r.addWidget(self.count, 1)
            else:
                bt = button(t)
                bt.clicked.connect(lambda _=False, d=d: self._count(d))
                r.addWidget(bt, 1)
        c1.addLayout(r)

        c2, _, self.key_hint = step("2. Đặt đèn lên phím Đô giữa", "")
        # Built once; refresh() shows the parts that fit the current LED controller type.
        self.key_box = QWidget()
        kb = QVBoxLayout(self.key_box)
        kb.setContentsMargins(0, 0, 0, 0)
        self.key_val = label("")
        self.key_val.setStyleSheet("font-size:20px;font-weight:800")
        kb.addWidget(self.key_val)
        r = QHBoxLayout()
        for t, d in (("◀ Sang trái", -1), ("Sang phải ▶", 1)):
            bt = button(t)
            bt.clicked.connect(lambda _=False, d=d: self._shift(d))
            r.addWidget(bt)
        kb.addLayout(r)
        r = QHBoxLayout()
        bc = button("Sáng Đô giữa")
        bc.clicked.connect(lambda: led.show_keys([M.MIDDLE_C]))
        be = button("Sáng 2 đầu")
        be.clicked.connect(self._show_ends)
        r.addWidget(bc)
        r.addWidget(be)
        kb.addLayout(r)
        c2.addWidget(self.key_box)
        c3, _, _ = step("3. Độ sáng", "")
        sl = QSlider(Qt.Horizontal)
        sl.setRange(10, 255)
        sl.setValue(s["led_brightness"])
        sl.valueChanged.connect(led.set_brightness)
        c3.addWidget(sl)
        done = button("Xong", "primary")
        done.clicked.connect(self._back)
        v.addWidget(done)
        v.addStretch(1)
        led.listeners.append(lambda *a: self.app.post(self.refresh))

    HINT_WS = ("Đèn đang sáng ở phím Đô giữa (C4). Nhấn ◀ hoặc ▶ để dời cả dải từng đèn một, tới khi đèn "
               "nằm ngay trên phím Đô giữa. “Thử đèn” chạy đúng ví dụ FastLED (đèn 0, 1, 2, 5, 9, 14, 19) "
               "rồi chạy qua từng phím.")
    HINT_NUS = ("Một đèn đang sáng cho phím Đô giữa (C4). Nhấn ◀ hoặc ▶ cho tới khi nó nằm ngay trên phím "
                "Đô giữa của đàn bạn. Nếu đèn chạy ngược chiều, hãy bấm nút còn lại.")
    HINT_SP = ("Bộ điều khiển SP611E sáng theo 16 vùng chứ không theo từng phím, nên không cần căn từng "
               "phím. Hãy dán dải đèn phủ đều toàn bộ bàn phím.")

    def _show_ends(self):
        led = self.app.led
        if led.is_ws:
            c = WS.config(self.app.settings)
            led.show_keys([c["lowest"], c["lowest"] + c["keys"] - 1])
        else:
            led.show_keys([M.PIANO_LOWEST, M.PIANO_HIGHEST])

    def _back(self):
        self.app.led.end_manual()
        self.win.show_page(self.win.home)

    def _count(self, d):
        led, s = self.app.led, self.app.settings
        if led.is_ws:
            # WS2812B: number of LEDs on the strip; light both ends so the last key can be checked.
            s["ws_num_leds"] = max(1, min(600, WS.config(s)["num_leds"] + d))
            self.refresh()
            c = WS.config(s)
            led.show_keys([c["lowest"], c["lowest"] + c["keys"] - 1])
            return
        self.app.led.set_length(max(1, min(150, self.app.settings["led_length"] + d)))
        self.refresh()
        if not self.app.led.per_key:
            self.app.led.test_lights()

    def _shift(self, d):
        s = self.app.settings
        if self.app.led.is_ws:
            s["ws_first_led"] = max(-100, min(300, s["ws_first_led"] + (-d if s["ws_reversed"] else d)))
            self.refresh()
            self.app.led.show_keys([M.MIDDLE_C])
            return
        s["organ_offset"] = max(M.PIANO_LOWEST - 12, min(M.PIANO_HIGHEST, s["organ_offset"] + d))
        self.refresh()
        self.app.led.show_keys([M.MIDDLE_C])

    def refresh(self):
        led, s = self.app.led, self.app.settings
        ok = led.state == led.CONNECTED
        self.status.setText(f"Đã kết nối: {led.device_name}" if ok else "Chưa kết nối đèn LED")
        self.status_hint.setText("Làm theo 3 bước bên dưới." if ok else "Bật dải đèn rồi nhấn Kết nối.")
        self.connect_btn.setVisible(not ok)
        self.count.setText(str(WS.config(s)["num_leds"] if led.is_ws else s["led_length"]))
        self.key_hint.setText(self.HINT_WS if led.is_ws else self.HINT_NUS if led.per_key else self.HINT_SP)
        self.key_box.setVisible(led.per_key)
        if led.per_key:
            o = s["organ_offset"]
            self.key_val.setText(f"Đèn đầu: {s['ws_first_led']}" if led.is_ws else
                                 f"Phím đầu tiên của dải: {M.note_name(o, 'letters')}{M.octave(o)}")
            if ok and self.isVisible():
                led.show_keys([M.MIDDLE_C])


class PlayerPage(Page, Listener):
    LOOKAHEAD = 2.5

    def __init__(self, win):
        Page.__init__(self)
        self.win, self.app = win, win.app
        self.s = self.app.settings
        self.geom = KeyGeom(60, 83)
        self.song = None
        self.engine: Engine | None = None
        self.synth = Synth(self.app.bank, self.s["program"])
        self.key = ""
        self.title = ""
        self.lesson = None
        self.fingering: dict[int, int] = {}
        self.practice = "both"
        self.coach = Coach()
        self.score = ScoreKeeper()
        self.pass_active = False
        self.wait_remaining: set[int] = set()
        self.pressed: dict[int, bool] = {}
        self.active: dict[int, str] = {}
        self.active_fingers: dict[int, int] = {}
        self.unsaved = 0.0
        self.tick = time.perf_counter()
        self.measures: list[float] = []
        self.dyn = LRN.Dynamics()
        self.rec = LRN.Recorder()
        self.hot_bar: int | None = None
        self._build()
        self.app.hub.listeners.append(self)
        self.app.led.listeners.append(lambda *a: self.app.post(self._led_chip))
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._frame)
        self.timer.start(33)

    # ---- layout ----
    def _build(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)
        top = QHBoxLayout()
        top.setContentsMargins(8, 8, 12, 4)
        back = button("‹", "round", tip="Quay lại")
        back.clicked.connect(self.leave)
        self.title_lbl = label("", "h2")
        opts = button("⚙", "round", tip="Tùy chọn luyện tập")
        opts.clicked.connect(self.show_options)
        self.chip = button("● LED tắt")
        self.chip.clicked.connect(self._chip_click)
        top.addWidget(back)
        top.addWidget(self.title_lbl, 1)
        top.addWidget(opts)
        top.addWidget(self.chip)
        root.addLayout(top)
        prog = QHBoxLayout()
        prog.setContentsMargins(16, 0, 16, 0)
        self.t_now, self.t_total = label("0:00", "mid"), label("0:00", "mid")
        self.seek = QSlider(Qt.Horizontal)
        self.seek.setRange(0, 1000)
        self.seek.sliderReleased.connect(self._seek_release)
        prog.addWidget(self.t_now)
        prog.addWidget(self.seek, 1)
        prog.addWidget(self.t_total)
        root.addLayout(prog)
        self.loopbar = LoopBar()
        self.loopbar.hide()
        self.loopbar.changed.connect(self._loop_changed)
        root.addWidget(self.loopbar)

        self.body = QBoxLayout(QBoxLayout.TopToBottom)
        self.body.setSpacing(0)
        root.addLayout(self.body, 1)
        music = QWidget()
        mv = QVBoxLayout(music)
        mv.setContentsMargins(0, 4, 0, 0)
        mv.setSpacing(0)
        self.staff = Staff()
        self.staff.hide()
        mv.addWidget(self.staff)
        self.roll = PianoRoll(self.geom)
        self.roll.scrub.connect(lambda t: (self.engine and self.engine.seek(t), setattr(self, "pass_active", False)))
        self.roll.tapped.connect(self._tap)
        mv.addWidget(self.roll, 1)
        self.keyboard = Keyboard(self.geom)
        self.keyboard.setFixedHeight(150)
        mv.addWidget(self.keyboard)
        self.overlay = label("", "overlay")
        self.overlay.setParent(self.roll)
        self.overlay.hide()
        self.mode_chip = label("", "chip")
        self.mode_chip.setParent(self.roll)
        self.mode_chip.move(8, 8)
        self.mode_chip.hide()
        self.feedback = label("", "chip")
        self.feedback.setParent(self.roll)
        self.feedback.hide()
        self.body.addWidget(music, 1)

        self.panel = QFrame()
        self.panel.setObjectName("panel")
        self.pgrid = QGridLayout(self.panel)
        self.pgrid.setContentsMargins(16, 14, 16, 16)
        self.hands = Segments([("left", "● Trái"), ("both", "Cả hai"), ("right", "● Phải")], self._set_practice)
        self.hands.buttons["left"].setStyleSheet("QPushButton{color:#7FB0FF}QPushButton:checked{color:#101218}")
        self.hands.buttons["right"].setStyleSheet("QPushButton{color:#F8B84E}QPushButton:checked{color:#101218}")
        sp = QFrame()
        sp.setObjectName("track")
        sh = QHBoxLayout(sp)
        sh.setContentsMargins(4, 4, 4, 4)
        self.slower = button("−", "seg", tip="Chậm hơn")
        self.faster = button("+", "seg", tip="Nhanh hơn")
        self.speed_lbl = label("100%")
        self.speed_lbl.setAlignment(Qt.AlignCenter)
        self.speed_lbl.setStyleSheet("font-weight:800;font-size:16px")
        self.slower.clicked.connect(lambda: self.set_speed(self.speed - 5))
        self.faster.clicked.connect(lambda: self.set_speed(self.speed + 5))
        sh.addWidget(self.slower, 1)
        sh.addWidget(self.speed_lbl, 1)
        sh.addWidget(self.faster, 1)
        self.speed_frame = sp
        trans = QHBoxLayout()
        self.restart_b = button("⟲", "round", tip="Về đầu")
        self.play_b = button("▶", "play", tip="Phát")
        self.repeat_b = button("🔁", "round", checkable=True, tip="Lặp lại")
        self.repeat_b.setText("↻")
        self.hot_b = button("🎯", "round", tip="Luyện chỗ khó")
        self.rec_b = button("⏺", "round", checkable=True, tip="Ghi âm phần bạn chơi")
        self.hot_b.clicked.connect(self.practise_hot_spot)
        self.rec_b.clicked.connect(self.toggle_record)
        for b in (self.hot_b, self.rec_b):
            b.setFixedSize(52, 52)
        self.restart_b.setFixedSize(52, 52)
        self.repeat_b.setFixedSize(52, 52)
        self.play_b.setFixedSize(72, 72)
        self.restart_b.clicked.connect(self.restart)
        self.play_b.clicked.connect(self.toggle_play)
        self.repeat_b.clicked.connect(self.toggle_repeat)
        trans.addStretch(1)
        for b, cap in ((self.hot_b, "Chỗ khó"), (self.restart_b, "Về đầu"), (self.play_b, "Phát"),
                       (self.repeat_b, "Lặp lại"), (self.rec_b, "Ghi âm")):
            col = QVBoxLayout()
            col.addWidget(b, 0, Qt.AlignHCenter)
            c = label(cap, "mid")
            c.setAlignment(Qt.AlignCenter)
            if b is self.play_b:
                self.play_cap = c
            col.addWidget(c)
            trans.addLayout(col)
            trans.addSpacing(18)
        trans.addStretch(1)
        self.trans = trans
        self.lbl_hands, self.lbl_speed = label("Tay", "mid"), label("Tốc độ", "mid")
        self.body.addWidget(self.panel)
        self._place_panel(False)
        QShortcut(QKeySequence(Qt.Key_Space), self, activated=self._space)
        for k in (Qt.Key_Right, Qt.Key_PageDown, Qt.Key_Return, Qt.Key_Down):
            QShortcut(QKeySequence(k), self, activated=self._pedal)

    def _place_panel(self, land: bool):
        g = self.pgrid
        for w in (self.lbl_hands, self.hands, self.lbl_speed, self.speed_frame):
            g.removeWidget(w)
        g.removeItem(self.trans)
        if land:
            g.addWidget(self.lbl_hands, 0, 0)
            g.addWidget(self.hands, 1, 0)
            g.addWidget(self.lbl_speed, 2, 0)
            g.addWidget(self.speed_frame, 3, 0)
            g.setRowStretch(4, 1)
            g.addLayout(self.trans, 5, 0)
            self.panel.setFixedWidth(290)
            self.panel.setMaximumHeight(16777215)
        else:
            g.setRowStretch(4, 0)
            g.addWidget(self.lbl_hands, 0, 0)
            g.addWidget(self.hands, 0, 1)
            g.addWidget(self.lbl_speed, 1, 0)
            g.addWidget(self.speed_frame, 1, 1)
            g.addLayout(self.trans, 2, 0, 1, 2)
            self.panel.setMinimumWidth(0)
            self.panel.setMaximumWidth(16777215)

    def resizeEvent(self, e):
        land = self.width() > self.height() * 1.1
        if land != getattr(self, "_land", None):
            self._land = land
            self.body.setDirection(QBoxLayout.LeftToRight if land else QBoxLayout.TopToBottom)
            self._place_panel(land)
            self.keyboard.setFixedHeight(112 if land else 150)
            self._apply_range()
        super().resizeEvent(e)
        self._place_overlays()

    def _place_overlays(self):
        r = self.roll
        self.overlay.adjustSize()
        self.overlay.move((r.width() - self.overlay.width()) // 2, (r.height() - self.overlay.height()) // 2)
        self.feedback.adjustSize()
        self.feedback.move(r.width() - self.feedback.width() - 8, 8)

    # ---- song ----
    def open(self, key: str, title: str):
        self.close_song()
        self.key, self.title = key, title
        self.lesson = self.app.lessons.find(key)
        try:
            song = M.load(self.app.path_for(key), title)
        except Exception as ex:
            QMessageBox.warning(self, "Lỗi", f"Không thể mở tệp MIDI này\n{ex}")
            return False
        M.assign_hands(song, self.s["hand_split"])
        self.song = song
        self.measures = song.measure_secs()
        self.fingering = Lessons.fingering(self.lesson, song) if self.lesson else \
            {id(n): n.finger for n in song.notes if n.finger}
        self.dyn.reset()
        self.hot_bar = None
        self.practice = {"R": "right", "L": "left"}.get(self.lesson.hands if self.lesson else "", self.practice)
        self.coach.lights = self.s["lights_mode"]
        self.synth.volume = self.s["volume"] / 100
        self.synth.start()
        self.engine = Engine(song, self.synth, self)
        self.engine.speed = self.s["speed"]
        self.title_lbl.setText(self.lesson.title if self.lesson else title)
        self.t_total.setText(fmt(song.duration))
        self.roll.set_song(song)
        self.staff.set_song(song)
        self.loopbar.set_song(song.duration, self.measures)
        self._hot_marks()
        self.pass_active = False
        self._apply_colors()
        self._apply_range()
        self._apply_hands()
        self._apply_display()
        self._apply_guidance()
        self._apply_engine()
        self._apply_speed()
        self._apply_repeat()
        self._mode_chip()
        self._led_chip()
        self.tick = time.perf_counter()
        if song.multi_instrument:
            QMessageBox.information(self, "Cảnh báo", "Bản nhạc này có thể gồm nhiều nhạc cụ.")
        if self.s["mic_input"]:
            self.app.mic.start()
        return True

    def close_song(self):
        if self.engine:
            self.engine.stop()
        self._flush(True)
        self.engine = None
        self.app.led.guidance = 1.0
        self.app.led.all_off()
        self.app.mic.stop()

    def leave(self):
        self.close_song()
        self.win.show_page(self.win.home)

    def _apply_range(self):
        land = getattr(self, "_land", False)
        mn = 3 if land else 2
        ps = [n.pitch for n in self.song.notes] if self.song else [60, 71]
        lo, hi = min(ps) // 12 * 12, max(ps) // 12 * 12 + 11
        up = True
        while (hi - lo + 1) // 12 < mn:
            cu, cd = hi + 12 <= M.PIANO_HIGHEST, lo - 12 >= M.PIANO_LOWEST
            if not cu and not cd:
                break
            if (up and cu) or not cd:
                hi += 12
            else:
                lo -= 12
            up = not up
        self.geom.low, self.geom.high = max(lo, M.PIANO_LOWEST), min(hi, M.PIANO_HIGHEST)
        self.roll.update()
        self.keyboard.update()

    def _apply_colors(self):
        c = {M.LEFT: self.s["color_left"], M.RIGHT: self.s["color_right"]}
        self.roll.colors = self.keyboard.colors = self.staff.colors = c

    def practised(self, n):
        return {"left": n.hand != M.RIGHT, "right": n.hand != M.LEFT}.get(self.practice, True)

    def _set_practice(self, p):
        self.practice = p
        self.pass_active = False
        self.coach.reset()
        self._apply_hands()
        self._apply_engine()

    def _apply_hands(self):
        self.hands.set(self.practice)
        sl, sr = self.practice != "right", self.practice != "left"
        for v in (self.roll, self.keyboard, self.staff):
            v.show_left, v.show_right = sl, sr
            v.update()

    def _apply_display(self):
        lm = self.s["label_mode"]
        self.keyboard.label_mode = self.roll.label_mode = lm
        self.staff.setVisible(self.s["show_staff"])
        self.roll.fingers = self.fingering if self.s["show_fingers"] else {}
        if not self.s["show_fingers"]:
            self.keyboard.fingers = {}
        self.roll.update()
        self.keyboard.update()

    def _apply_guidance(self):
        g = self.coach.guidance
        for v in (self.roll, self.keyboard, self.staff):
            v.guidance = g
            v.update()
        self.app.led.guidance = g

    def effective_mode(self):
        m = self.s["practice_mode"]
        return "normal" if m == "wait" and not self.app.hub.connected else m

    def _apply_engine(self):
        e = self.engine
        if not e:
            return
        mode = self.effective_mode()
        e.gate_filter = self.practised
        e.gate_mode = mode in ("wait", "step")
        e.metronome = self.s["metronome"]
        src = self.app.hub.source
        e.mute_filter = {MIDI: self.practised, MIC: (lambda n: True)}.get(src, lambda n: False)
        if not e.gate_mode:
            self.overlay.hide()

    @property
    def speed(self):
        return self.engine.speed if self.engine else self.s["speed"]

    def set_speed(self, v):
        v = max(MIN_SPEED, min(MAX_SPEED, v))
        if self.engine:
            self.engine.speed = v
        self.s["speed"] = v
        self._apply_speed()

    def _apply_speed(self):
        self.speed_lbl.setText(f"{self.speed}%")
        self.slower.setEnabled(self.speed > MIN_SPEED)
        self.faster.setEnabled(self.speed < MAX_SPEED)

    def _mode_chip(self):
        t = {"wait": "Chờ phím", "step": "Từng bước · nhấp để đi tiếp"}.get(self.effective_mode())
        self.mode_chip.setText(t or "")
        self.mode_chip.adjustSize()
        self.mode_chip.setVisible(bool(t))

    def _led_chip(self):
        led = self.app.led
        ok = led.state == led.CONNECTED
        self.chip.setText({led.CONNECTED: "● LED bật", led.CONNECTING: "Đang kết nối…"}.get(led.state, "● LED tắt"))
        self.chip.setStyleSheet(f"background:{'#13352B' if ok else SURF2};color:{'#6EE7B7' if ok else TEXT_MID}")

    def _chip_click(self):
        led = self.app.led
        if led.state != led.CONNECTED:
            BleDialog(self.app, self, lambda d: led.connect(d[1], d[0])).exec()

    # ---- transport ----
    def toggle_play(self):
        e = self.engine
        if not e:
            return
        if e.playing:
            e.pause()
            return
        if self.s["practice_mode"] == "wait" and not self.app.hub.connected:
            r = QMessageBox.question(self, "Chế độ Chờ phím cần nối đàn",
                                     "Cắm đàn điện bằng cáp USB hoặc bật nghe qua micro. "
                                     "Bạn có muốn dùng chế độ Từng bước (nhấp để đi tiếp) thay thế?")
            if r != QMessageBox.Yes:
                return
            self.s["practice_mode"] = "step"
            self._mode_chip()
        self._apply_engine()
        if not self.pass_active:
            self.start_pass(e.pos)
        self.tick = time.perf_counter()
        e.play_with_count_in() if self.s["count_in"] else e.play()

    def _space(self):
        e = self.engine
        if e and e.waiting is not None and self.s["practice_mode"] == "step":
            self.step_forward()
        else:
            self.toggle_play()

    def _pedal(self):
        e = self.engine
        if e and e.waiting is not None and self.s["practice_mode"] == "step":
            self.step_forward()

    def _tap(self):
        self._pedal()

    def restart(self):
        e = self.engine
        if not e:
            return
        e.stop()
        self.clear_repeat()
        self.app.led.all_off()
        self.active.clear()
        self.active_fingers.clear()
        self.overlay.hide()
        self.pass_active = False

    def _seek_release(self):
        if self.engine and self.song:
            self.engine.seek(self.seek.value() / 1000 * self.song.duration)
            self.pass_active = False

    # ---- repeat ----
    def toggle_repeat(self):
        e, s = self.engine, self.song
        if not e or not s:
            return
        if e.looping:
            self.clear_repeat()
            return
        pos = e.pos
        idx = max([i for i, m in enumerate(self.measures) if m <= pos + 0.01], default=-1)
        if idx >= 0 and idx + 2 < len(self.measures):
            a, b = self.measures[idx], self.measures[idx + 2]
        else:
            a, b = max(0.0, pos), min(pos + 8, s.duration)
        if b - a < 0.5:
            self.repeat_b.setChecked(False)
            return
        e.loop_start, e.loop_end, e.looping = a, b, True
        if not e.playing:
            e.seek(a)
        self.coach.reset()
        self.start_pass(a)
        self._apply_repeat()

    def clear_repeat(self):
        if self.engine:
            self.engine.looping = False
            self.engine.loop_start = self.engine.loop_end = None
        self._apply_repeat()

    def _apply_repeat(self):
        e = self.engine
        on = bool(e and e.looping)
        self.repeat_b.setChecked(on)
        self.loopbar.setVisible(on)
        if on:
            self.loopbar.set_range(e.loop_start or 0, e.loop_end or 0)
            self.roll.loop = (e.loop_start, e.loop_end)
        else:
            self.roll.loop = (None, None)
        self.roll.update()

    def _loop_changed(self, a, b):
        e = self.engine
        if not e:
            return
        e.loop_start, e.loop_end = a, b
        self.roll.loop = (a, b)
        if not (a <= e.pos < b):
            e.seek(a)
        self.coach.reset()
        self.start_pass(a)

    # ---- options ----
    def show_options(self):
        self.opt = OptionsDialog(self.app, self, bool(self.song and M.uses_pitch_split(self.song)), self._opt_changed)
        self.opt.exec()
        self.opt = None

    def _opt_changed(self, what):
        if what == "led":
            self.app.led.set_preview([])
            self.app.led._last_frame = None
            self.app.led._flush()
            return
        if what == "mode":
            self.pass_active = False
            self._apply_engine()
            self._mode_chip()
        elif what == "lights":
            self.coach.lights = self.s["lights_mode"]
            self._apply_guidance()
        elif what in ("label_mode", "labels", "show_staff", "show_fingers"):
            self._apply_display()
        elif what == "metronome" and self.engine:
            self.engine.metronome = self.s["metronome"]
        elif what == "split" and self.song:
            M.assign_hands(self.song, self.s["hand_split"])
            self.fingering = Lessons.fingering(self.lesson, self.song) if self.lesson else \
                {id(n): n.finger for n in self.song.notes if n.finger}
            self.pass_active = False
            self._apply_display()
            self._apply_hands()

    # ---- passes ----
    def start_pass(self, frm):
        s, e = self.song, self.engine
        end = (e.loop_end if e and e.looping else None) or s.duration
        self.score.start_pass([n for n in s.notes if self.practised(n) and frm - 1e-3 <= n.start < end])
        self.pass_active = True

    def _on_pass(self):
        e = self.engine
        if not e:
            return
        if self.app.hub.connected and self.pass_active:
            up_to = e.loop_end or e.pos
            self._record_misses(up_to)
            r = self.score.finish_pass(up_to)
            self.app.stats.record_result(self.key, r, False, self.speed)
            if self.hot_bar is not None and r.clean:
                self.app.hot.add_clean(self.key, self.hot_bar)
                self._hot_marks()
            ns = self.coach.on_pass(r, self.speed, self.s["auto_speed"])
            if ns is not None:
                msg = (f"Rất tốt! 3 lần đúng liên tiếp — tăng lên {ns}%" if ns > self.speed
                       else f"Chậm lại một chút: {ns}%")
                self.set_speed(ns)
            else:
                msg = f"Lượt vừa rồi: đúng {r.accuracy}%, sai {r.wrong} phím" if r.total else ""
            adv = self.dyn.advice()
            if adv:
                msg = (msg + " · " if msg else "") + LRN.ADVICE_VI[adv]
            self.dyn.reset()
            if msg:
                self._show_feedback(msg, True, 2500)
        self.start_pass(e.loop_start or 0)

    def _on_finished(self):
        s = self.song
        self._flush(True)
        if self.app.hub.connected and self.pass_active:
            self._record_misses(s.duration)
            r = self.score.finish_pass(s.duration)
            self.app.stats.record_result(self.key, r, True, self.speed)
            msg = f"Chính xác {r.accuracy}% ({r.hits}/{r.total} nốt) · {r.wrong} phím sai"
            if r.hits:
                o = r.mean_offset_ms
                msg += ("\nTrung bình bạn chơi trễ %d ms." % o if o > 60 else
                        "\nTrung bình bạn chơi sớm %d ms." % -o if o < -60 else "\nNhịp rất đều!")
            adv = self.dyn.advice()
            if adv:
                msg += "\n" + LRN.ADVICE_VI[adv] + "."
            self.dyn.reset()
            hot = self.app.hot.worst(self.key)
            if hot is not None:
                msg += f"\nChỗ khó nhất: ô nhịp {hot + 1} — bấm 🎯 để luyện riêng."
            self._hot_marks()
        else:
            self.app.stats.mark_completed(self.key, self.speed)
            msg = "Bạn đã chơi hết bài. Nối đàn để được chấm điểm."
        self.pass_active = False
        box = QMessageBox(self)
        box.setWindowTitle("Hoàn thành bài!")
        box.setText(msg)
        again = box.addButton("Chơi lại", QMessageBox.AcceptRole)
        nxt = self.app.lessons.next(self.key)
        nb = box.addButton("Bài tiếp theo", QMessageBox.ActionRole) if nxt else None
        lb = box.addButton("Thử không cần đèn", QMessageBox.ActionRole) if self.coach.lights != Coach.OFF else None
        box.addButton("Đóng", QMessageBox.RejectRole)
        box.exec()
        c = box.clickedButton()
        if c is again:
            self.restart()
            self.toggle_play()
        elif nb is not None and c is nb:
            self.win.open_song(nxt.key, nxt.title)
        elif lb is not None and c is lb:
            self.s["lights_mode"] = "off"
            self.coach.lights = Coach.OFF
            self._apply_guidance()
            self.restart()
            self.toggle_play()

    def _flush(self, force=False):
        now = time.perf_counter()
        if self.engine and self.engine.playing:
            self.unsaved += min(2.0, max(0.0, now - self.tick))
        self.tick = now
        if self.key and (force or self.unsaved >= 15) and self.unsaved >= 1:
            self.app.stats.add_practice(self.key, int(self.unsaved), self.speed)
            self.unsaved -= int(self.unsaved)

    # ---- wait / step ----
    def _show_wait(self, chord):
        for n in chord:
            self.app.led.key_on(n.pitch, n.hand, n.velocity)
        if chord:
            self._update_preview(chord[0].start)
        if self.s["practice_mode"] == "wait":
            if self.app.hub.source == MIC:
                self.wait_remaining = {max(n.pitch for n in chord)}
            else:
                self.wait_remaining = {n.pitch for n in chord}
            lm = "solfege" if self.s["label_mode"] == "solfege" else "letters"
            names = "  ".join(f"{M.note_name(p, lm)}{M.octave(p)}" for p in sorted({n.pitch for n in chord}))
            self._overlay(f"Bấm: {names}")
        else:
            self._overlay("Nhấp để đi tiếp")

    def step_forward(self):
        e = self.engine
        if e and e.waiting is not None:
            self.score.mark_chord_done(e.waiting)
            e.release_gate()
            self.overlay.hide()

    def _overlay(self, text, big=False):
        self.overlay.setText(text)
        self.overlay.setStyleSheet("font-size:56px" if big else "")
        self.overlay.show()
        self._place_overlays()

    def _show_feedback(self, text, good, ms=1200):
        self.feedback.setText(text)
        self.feedback.setStyleSheet(f"background:{'#13352B' if good else '#3A1416'}")
        self.feedback.show()
        self._place_overlays()
        QTimer.singleShot(ms, self.feedback.hide)

    # ---- input hub listener (main thread) ----
    def on_note_on(self, pitch, vel):
        e = self.engine
        chord = e.waiting if e else None
        if self.rec.active:
            self.rec.note(time.perf_counter(), pitch, vel)
        led = self.app.led
        if e and chord is not None and self.s["practice_mode"] == "wait":
            if any(n.pitch == pitch for n in chord):
                ok = True
                led.flash(pitch, LRN.GREEN, 150)
                if self.app.hub.source == MIDI:
                    self.dyn.add(next(n.velocity for n in chord if n.pitch == pitch), vel)
                self.wait_remaining.discard(pitch)
                if not self.wait_remaining:
                    self.score.mark_chord_done(chord)
                    if self.coach.on_correct():
                        self._apply_guidance()
                    e.release_gate()
                    self.overlay.hide()
            else:
                ok = False
                self.score.count_wrong()
                led.flash(pitch, LRN.RED, 300)
                self._miss_at(e.pos)
                if self.coach.on_error():
                    self._apply_guidance()
                self._show_feedback("Sai phím", False)
        elif e and e.playing and not e.counting_in and self.pass_active:
            v = self.score.on_key(pitch, e.pos, self.speed)
            ok = v != WRONG
            if v == HIT:
                led.flash(pitch, LRN.timing_flash(self.score.last_offset_ms), 180)
                if self.app.hub.source == MIDI and self.score.last_note is not None:
                    self.dyn.add(self.score.last_note.velocity, vel)
            if v == HIT and self.coach.on_correct():
                self._apply_guidance()
            if v == WRONG:
                led.flash(pitch, LRN.RED, 300)
                self._miss_at(e.pos)
                if self.coach.on_error():
                    self._apply_guidance()
                self._show_feedback("Sai phím", False)
        else:
            ok = True
            if not (e and e.playing):
                self.app.led.key_on(pitch, M.LEFT if pitch < self.s["hand_split"] else M.RIGHT, vel)
        self.pressed[pitch] = ok
        self.keyboard.pressed = dict(self.pressed)
        self.keyboard.update()

    def on_note_off(self, pitch):
        if self.rec.active:
            self.rec.note(time.perf_counter(), pitch, 0)
        self.pressed.pop(pitch, None)
        self.keyboard.pressed = dict(self.pressed)
        self.keyboard.update()
        if not (self.engine and self.engine.playing):
            self.app.led.key_off(pitch)

    # ---- learning features ----
    def _update_preview(self, after: float):
        """Next note of each practised hand after ``after`` glows dimly on the strip."""
        s = self.song
        if not s or not self.s["led_preview"]:
            self.app.led.set_preview([])
            return
        if getattr(self, "_starts_of", None) is not s:
            self._starts, self._starts_of = [n.start for n in s.by_time], s
        i = bisect.bisect_right(self._starts, after + 0.02)
        nxt: dict[str, int] = {}
        for n in s.by_time[i:i + 64]:
            if n.start <= after + 0.02 or not self.practised(n):
                continue
            nxt.setdefault(n.hand, n.pitch)
            if len(nxt) >= 2:
                break
        self.app.led.set_preview([(p, h) for h, p in nxt.items()])

    def _miss_at(self, sec: float):
        if self.key and self.measures:
            self.app.hot.add_miss(self.key, LRN.bar_of(self.measures, sec))
            self._hot_marks()

    def _record_misses(self, up_to: float):
        if not (self.key and self.measures):
            return
        bars: dict[int, int] = {}
        for n in self.score.missed(up_to):
            b = LRN.bar_of(self.measures, n.start)
            bars[b] = bars.get(b, 0) + 1
        for b, c in bars.items():
            self.app.hot.add_miss(self.key, b, c)
        if bars:
            self._hot_marks()

    def _hot_marks(self):
        c = self.app.hot.counts(self.key) if self.key else {}
        spans = []
        for b in c:
            if b < len(self.measures):
                spans.append(LRN.loop_for_bar(self.measures, self.song.duration if self.song else 0, b))
        self.loopbar.hot = spans
        self.loopbar.update()
        self.hot_b.setToolTip(f"Luyện chỗ khó ({len(c)} ô nhịp hay sai)" if c else "Luyện chỗ khó")

    def practise_hot_spot(self):
        """Loop the bar with the most mistakes at 65 %, +10 % after every 3 clean plays (Duke et al.)."""
        e, s = self.engine, self.song
        if not e or not s:
            return
        bar = self.app.hot.worst(self.key)
        if bar is None:
            self._show_feedback("Chưa có chỗ khó — nối đàn và chơi thử, ứng dụng sẽ tự tìm", True, 3000)
            return
        a, b = LRN.loop_for_bar(self.measures, s.duration, bar)
        if b - a < 0.3:
            return
        self.hot_bar = bar
        e.loop_start, e.loop_end, e.looping = a, b, True
        self.s["auto_speed"] = True
        self.set_speed(min(self.speed, LRN.HOTSPOT_SPEED))
        e.seek(a)
        self.coach.reset()
        self.start_pass(a)
        self._apply_repeat()
        self._show_feedback(f"Luyện ô nhịp {bar + 1} ở {self.speed}% · tự tăng tốc sau 3 lần đúng", True, 3000)

    def toggle_record(self):
        if not self.rec.active:
            self.rec.start(time.perf_counter())
            self.rec_b.setChecked(True)
            self._show_feedback("Đang ghi âm phần bạn chơi…", True, 2000)
            return
        n = self.rec.stop()
        self.rec_b.setChecked(False)
        if n == 0:
            self._show_feedback("Chưa ghi được nốt nào — hãy nối đàn", False, 2500)
            return
        folder = os.path.join(data_dir(), "recordings")
        os.makedirs(folder, exist_ok=True)
        name = f"{self.title or 'Bản ghi'} – {time.strftime('%d-%m %H.%M')}"
        path = os.path.join(folder, "".join(c for c in name if c not in '\\/:*?"<>|') + ".mid")
        bpm = int(round(60_000_000 / self.song.tempos[0][1])) if self.song else 120
        with open(path, "wb") as f:
            f.write(self.rec.midi_bytes(bpm))
        self.app.add_recent(path, "Bản ghi: " + name)
        box = QMessageBox(self)
        box.setWindowTitle("Đã ghi âm")
        box.setText(f"Đã lưu {n} nốt bạn chơi thành tệp MIDI:\n{path}\n\nBản ghi có trong danh sách bài của bạn.")
        op = box.addButton("Mở để nghe lại", QMessageBox.AcceptRole)
        box.addButton("Đóng", QMessageBox.RejectRole)
        box.exec()
        if box.clickedButton() is op:
            self.win.open_song(path, "Bản ghi: " + name)

    def on_input_state(self, src, name):
        self._apply_engine()
        self._mode_chip()
        if getattr(self, "opt", None):
            self.opt.update_input()

    # ---- engine listener (engine thread -> post) ----
    def on_note_start(self, n):
        self.app.post(lambda: self._note_start(n))

    def _note_start(self, n):
        self.active[n.pitch] = n.hand
        f = self.fingering.get(id(n))
        if f:
            self.active_fingers[n.pitch] = f
        self.app.led.key_on(n.pitch, n.hand, n.velocity)
        self._update_preview(n.start)

    def on_note_end(self, n):
        self.app.post(lambda: self._note_end(n))

    def _note_end(self, n):
        self.active.pop(n.pitch, None)
        self.active_fingers.pop(n.pitch, None)
        self.app.led.key_off(n.pitch)

    def on_state(self, playing):
        self.app.led.playing = playing
        self.app.post(lambda: self._state_ui(playing))

    def _state_ui(self, playing):
        self.play_b.setText("❚❚" if playing else "▶")
        self.play_cap.setText("Tạm dừng" if playing else "Phát")
        if not playing:
            self._flush(True)
        self.tick = time.perf_counter()

    def on_song_end(self):
        self.app.post(lambda: (self.active.clear(), self.app.led.all_off(), self._on_finished()))

    def on_waiting(self, chord):
        self.app.post(lambda: self._show_wait(chord))

    def on_count_in(self, left):
        self.app.post(lambda: self._overlay(str(left), True) if left else self.overlay.hide())

    def on_loop_wrapped(self):
        self.app.post(self._on_pass)

    # ---- frame timer (30 fps) ----
    def _frame(self):
        e, s = self.engine, self.song
        if not e or not s or not self.isVisible():
            return
        pos = e.pos
        self.roll.pos = pos
        self.roll.update()
        if self.staff.isVisible():
            self.staff.pos = pos
            self.staff.update()
        if not self.seek.isSliderDown():
            self.t_now.setText(fmt(pos))
            self.seek.setValue(int(pos / s.duration * 1000) if s.duration else 0)
        self._flush()
        up, fingers = {}, dict(self.active_fingers)
        show_f = self.s["show_fingers"] and self.fingering
        for n in s.by_time:
            if n.start < pos:
                continue
            if n.start > pos + self.LOOKAHEAD:
                break
            if n.pitch not in self.active:
                up[n.pitch] = n.hand
                if show_f and n.pitch not in fingers and id(n) in self.fingering:
                    fingers[n.pitch] = self.fingering[id(n)]
        self.keyboard.active = dict(self.active)
        self.keyboard.upcoming = up
        if show_f:
            self.keyboard.fingers = fingers
        self.keyboard.update()


def fmt(sec):
    sec = max(0, int(sec))
    return f"{sec // 60}:{sec % 60:02d}"


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.app = App()
        self.setWindowTitle("LED Piano")
        self.resize(QSize(460, 900))
        self.stack = QStackedWidget()
        self.setCentralWidget(self.stack)
        self.home = HomePage(self)
        self.lessons = LessonsPage(self)
        self.player = PlayerPage(self)
        self.wizard = WizardPage(self)
        for p in (self.home, self.lessons, self.player, self.wizard):
            self.stack.addWidget(p)
        self.show_page(self.home)
        QTimer.singleShot(500, self.app.led.auto_connect)

    def show_page(self, page):
        if page is not self.player and self.stack.currentWidget() is self.player:
            self.player.close_song()
        if hasattr(page, "refresh"):
            page.refresh()
        self.stack.setCurrentWidget(page)

    def open_song(self, key, title):
        self.app.add_recent(key, title)
        self.stack.setCurrentWidget(self.player)
        if not self.player.open(key, title):
            self.show_page(self.home)

    def closeEvent(self, e):
        self.player.close_song()
        self.player.synth.stop()
        super().closeEvent(e)


def main():
    qa = QApplication(sys.argv)
    qa.setApplicationName("LED Piano")
    qa.setStyleSheet(QSS)
    w = Window()
    w.show()
    sys.exit(qa.exec())


if __name__ == "__main__":
    main()
