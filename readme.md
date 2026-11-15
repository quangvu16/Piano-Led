# LED Piano

Learn piano with a **WS2812B LED strip above the keys**. The app plays the song, lights each key you
should press next (green = right, red = wrong in instant-feedback mode), and listens to your digital
piano, MIDI, or the microphone.

One folder, five parts:

| Folder | What it is |
|---|---|
| [LedPianoApp/](LedPianoApp/) | **Android app** (Kotlin, minSdk 26, targetSdk 36) |
| [LedPianoApple/](LedPianoApple/) | **iPhone / iPad / Mac app** (SwiftUI, one target for iOS 17+ and macOS 14+) |
| [LedPianoPy/](LedPianoPy/) | Desktop app for Windows / macOS / Linux (Python) |
| [LedPianoStrip/](LedPianoStrip/) | **ESP32 firmware** that drives the LED strip (+ Adalight firmware for Arduino) |
| [analysis/](analysis/) | Notes and extracts from reference apps (BanlanX, Led Organ) |

---

## How it works

```
 MIDI / Bluetooth piano / mic ──►  App (Android · iOS · Mac · Python)
                                        │
                     Bluetooth LE · USB · Wi-Fi (TCP 7777) · WLED · Art-Net · E1.31 · DDP
                                        ▼
                              ESP32 + WS2812B strip over the keys
                                        │
                              one LED per key lights up
```

The apps find the strip by themselves (Bluetooth LE advertisement, USB serial, or mDNS/UDP discovery
over Wi-Fi). Strip settings — density, key count, LED count, alignment, direction, hand colours — are
configured **in the app**, so the firmware is uploaded once and never edited again.

Full details of every connection method, the `A5 …` message protocol and the firmware options are in
[LedPianoStrip/README.md](LedPianoStrip/README.md).

---

## Wiring (ESP32 + WS2812B)

### Parts

| Part | Note |
|---|---|
| ESP32 dev board: **WROOM-32**, **S3-DevKitC-1 N16R8**, or **C3 Super Mini** | Bluetooth LE + Wi-Fi |
| WS2812B strip, 96–144 LEDs/m, ≈1.25 m for 88 keys | sparser strips share LEDs between keys |
| 5 V supply, 3–4 A | firmware caps current (`MAX_MILLIAMPS`) |
| 330 Ω resistor + 1000 µF / 16 V capacitor | protects data line, smooths supply |

### Standard wiring (ESP32-WROOM-32 and S3)

```
ESP32 GPIO 16 ──330 Ω──► DIN   (strip input end; arrow points away from it)
5 V supply + ──────────► 5V    (+ capacitor + leg)
5 V supply − ──────────► GND   (− capacitor − leg) ──► ESP32 GND   ← grounds must be joined
```

Power the ESP32 by USB, or from the same 5 V supply into its 5V/VIN pin.

### Variants

| Board | Data pin | Notes |
|---|---|---|
| ESP32-WROOM-32 | GPIO 16 | avoid boot/flash pins 0, 2, 12, 15, 6–11 |
| ESP32-S3-DevKitC N16R8 | GPIO 16 | avoid 0/3/45/46, USB 19/20, flash/PSRAM, COM 43/44, board LED |
| ESP32-C3 Super Mini | **GPIO 10** | avoid boot pins 2/8/9, USB 18/19; add a 74AHCT125 level shifter if the first LED flickers |
| Arduino Uno/Nano (Adalight) | D7 default | 330 Ω to DIN; optional HC-05 Bluetooth module |

---

## Android app pages

Build: `cd LedPianoApp && ./gradlew assembleDebug` →
`app/build/outputs/apk/debug/app-debug.apk`
(or open in Android Studio). Package `com.ledpiano.app`.

| Screen | Activity | What it does |
|---|---|---|
| **Home** (launcher) | `MainActivity` | LED status bar + **Kết nối/Ngắt**, daily goal & streak card (week bars), **Tiếp tục bài**, today's plan (5-level course), quick buttons — **Bài học**, **Nói tên bài** (voice), **Bàn phím đàn**, **Căn đèn LED** — and *Bài hát của bạn* songs list with **Thêm bài hát** (.mid / MusicXML import) |
| **Practice / Player** | `PlayerActivity` | The main screen: falling notes, zoomable keyboard, **Tay** (Left / Both / Right), speed 25–100 %, Play / Restart / Repeat, record. Options sheet: practice mode (Normal / Wait / Step), lights (Full / Fade / Off), note labels (C / letters / solfège), count-in, metronome, auto-speed, staff, finger numbers, LED feedback & preview, **Gửi bài này vào ESP32**, input source & Bluetooth MIDI pairing |
| **Lessons** | `LessonsActivity` | Lộ trình 5 cấp độ + sample songs (Vietnamese / English / other samples); tap to open in the player |
| **Căn đèn LED** | `LedAlignActivity` | 3-step strip alignment: (1) LED count −/+, (2) place a light on middle C ◀ ▶, (3) direction / density; **Thử đèn** runs the FastLED test pattern |
| **Bàn phím đàn** | `KeyboardActivity` | Free-play keyboard — tap keys, the matching LED lights while held; octave shift ◀ ▶ |
| **Cài đặt** | `SettingsActivity` | LED transport & effect, brightness, zones, WS2812B layout (LED count / density / keys / direction), hand colours, instrument & volume, sound, reconnection, plus shortcuts to LED alignment |
| **Voice** | `voice.VoiceActivity` | Xiaozhi chat — speak a song name ("Mở bài …") to open and optionally autoplay it (6-digit pairing code at xiaozhi.me) |
| **USB attach** | `UsbAttachActivity` | Transparent helper — when an ESP32 is plugged in by USB-OTG, connects automatically and closes |

Layouts: portrait and landscape (`layout/` + `layout-land/`).

---

## iOS / Mac app pages

Build: open `LedPianoApple/LedPiano.xcodeproj` in Xcode 16+, pick a team under
*Signing & Capabilities*, then Run (⌘R). If the project file won't open:
`brew install xcodegen && xcodegen` (regenerates from `project.yml`).
One target builds for **iOS 17+** and **macOS 14+**.

Navigation is a `NavigationStack` from `RootView` with sheets on top:

| Screen | Type | What it does |
|---|---|---|
| **Home** | `HomeView` (root) | Title + settings gear, LED card — **Kết nối / Ngắt**, **Thử đèn**, **Căn đèn với phím** — daily goal, week bars, **Tiếp tục bài**, plan card, **Nói tên bài** (Xiaozhi), *Bài hát của bạn* list with import (.mid / MusicXML) and swipe-to-delete |
| **Lessons** | `LessonsView` | 5-level course + sample songs; opens the player |
| **Practice / Player** | `PlayerView` | Falling notes, keyboard, hands, speed, play controls; landscape layout on iPhone/iPad |
| **Options** | `OptionsSheet` (sheet) | Practice mode, lights mode, instrument, input, LED options — same feature set as Android |
| **Căn đèn LED** | `LedWizardView` | 3-step alignment wizard (count → middle C → direction), **Thử đèn** |
| **Cài đặt** | `SettingsSheet` (sheet) | LED transport, WS2812B layout, hand colours, sound, daily goal |
| **Bluetooth picker** | `BlePicker` (sheet) | Scan & connect to the ESP32 / controllers by BLE |
| **Bluetooth MIDI pairing** | `BluetoothMidiPairing` | Pair a Bluetooth-MIDI piano (iOS) |
| **Instrument picker** | `InstrumentPicker` | Choose the synthesizer sound |
| **Voice** | `VoiceView` (sheet) | Xiaozhi conversation — speak a song name to open it |

Notes: USB strip connection is **not** available on iOS (use Bluetooth or Wi-Fi); the mic input is
single-note detection (experimental). Opus audio comes from the `swift-opus` Swift package.

---

## Quick start

1. **Flash the ESP32** — Arduino IDE, install *esp32* by Espressif + *FastLED*, open
   `LedPianoStrip/LedPianoStrip.ino`, check `LED_PIN`, upload (partition scheme *Minimal SPIFFS*).
   Wiring above.
2. **Install an app** — Android APK (build above), Xcode run for iOS/Mac, or
   `python3 -m pip install -r requirements.txt && python3 -m ledpiano` for desktop.
3. **Connect** — open the app → **Kết nối** → pick the board (it appears as *LED Piano Strip*).
4. **Align the strip** — *Căn đèn LED* / *Căn đèn với phím*: set LED count, put a light on middle C.
5. **Play** — pick a lesson or import a `.mid`, press Play, and follow the lights.

### Per-platform connection support

| | Android | iPhone/iPad | Mac | Python |
|---|---|---|---|---|
| Bluetooth LE | ✅ | ✅ | ✅ | ✅ |
| USB cable | ✅ | ❌ | ✅ | ✅ |
| Wi-Fi (TCP 7777) | ✅ | ✅ | ✅ | ✅ |
| WLED / Art-Net / E1.31 / DDP | ✅ | ✅ | ✅ | ✅ |

---

## Further reading

- [LedPianoStrip/README.md](LedPianoStrip/README.md) — firmware, full wiring per board, all 11
  connection methods, message protocol, OTA updates, WLED/Art-Net
- [LedPianoApple/README.md](LedPianoApple/README.md) — Xcode build notes, USB/MIDI setup
- [LedPianoPy/README.md](LedPianoPy/README.md) — desktop run & tests, Xiaozhi voice setup
