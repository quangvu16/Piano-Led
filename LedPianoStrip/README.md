# LED Piano Strip 2 (ESP32 + WS2812B)

> **Note:** this firmware was checked on a PC (logic tests + a syntax check against stand-in Arduino
> headers), but it has **not been compiled for the ESP32 yet**: the ESP32 compiler could not be
> downloaded where it was written. Compile it in Arduino IDE (steps below); if anything fails, send the
> error message.

## New in version 2
| Feature | How to use it |
|---|---|
| **Bluetooth MIDI piano → ESP32** | Turn on your piano's Bluetooth MIDI. The ESP32 finds it and connects by itself (it looks every 10 s, after 2 minutes once a minute). The notes go to the app over the same link, so the phone needs only the ESP32. |
| **Instant green/red** | While the app is open, the ESP32 knows which keys you should press next and flashes the key green (right) or red (wrong) at once, before the phone even hears it. |
| **Synthesia key lights** | In Synthesia: Settings → Music Devices → output **"LED Piano Strip"** → Key Lights. Channels 1–5 = left-hand colour, the others = right-hand colour ("Finger-based channel" mode). On a computer, pair it first (Mac: Audio MIDI Setup → Bluetooth). |
| **Practise without the phone** | In the app: Tùy chọn → **"Gửi bài này vào ESP32"** (up to 3000 notes). Then press **BOOT** on the ESP32: the next chord lights up and waits for you. Short press = skip to the next chord, long press (1 s) = stop. With no song stored, 3 LEDs blink red. |
| **Wi-Fi link for the apps** | Over Wi-Fi the apps get every feature (TCP 7777) and find the board by themselves. The firmware also receives **E1.31 / sACN** (UDP 5568) and **DDP** (UDP 4048). |
| **Wi-Fi web page** | First time: join the Wi-Fi **"LED-Piano-XXXX"** (password `ledpiano`) and open http://192.168.4.1. Enter your home Wi-Fi; after a restart it is at **http://ledpiano.local** (or the IP printed on Serial). Settings: brightness, current limit, auto-off, free-play effect, an optional page password (user `admin`). |
| **Firmware update over Wi-Fi (OTA)** | Arduino IDE → Sketch → Export Compiled Binary, then upload the `.bin` on the web page. |
| **WLED / Art-Net receiver** | Anything that speaks WLED realtime UDP (port 21324: WARLS, DRGB, DRGBW, DNRGB) or Art-Net (port 6454, 170 LEDs per universe) can drive the strip over Wi-Fi, including the apps' "WLED" and "Art-Net" options with the ESP32's IP. |
| **Free play, auto-off, power limit** | With a Bluetooth piano and no app, the keys you play light up and fade (effect "Màu tay phải" or "Cầu vồng theo lực bấm": soft = blue, loud = red). LEDs switch off after 10 idle minutes (any note, message or button wakes them). The current limit keeps a small 5 V supply safe. |

Settings from the app (density, keys, LED count, alignment, direction, hand colours) are **remembered
by the ESP32**, so Led Organ mode, practising without the phone and Synthesia use them too.

Firmware so the LED Piano apps (Android, iPhone/iPad, Mac, Python) control a WS2812B strip
**LED by LED**, like `leds[i] = CRGB(r, g, b); FastLED.show()` in the FastLED example — one LED above
each key the learner should play, everything else off.

The apps find it by themselves and switch to **"WS2812B (ESP32) – ứng dụng điều khiển từng đèn"**.
Everything you would normally change in the code — strip density, piano size, number of LEDs,
alignment, direction, colours, brightness — is set **in the app** (Cài đặt → Dải WS2812B, and
"Căn đèn LED"), so you upload the firmware once and never again. "Thử đèn" plays the exact FastLED
example (LEDs 0, 1, 2, 5, 9, 14, 19, 500 ms apart, same colours), then runs a light over every key.

It also still understands the Led Organ key messages (then it uses the settings at the top of the
sketch).

Based on HowToMechatronics, *How To Control WS2812B Individually Addressable LEDs using Arduino* (FastLED).

## Parts
| Part | Note |
|---|---|
| **ESP32-WROOM-32** dev board, or **ESP32-S3-DevKitC-1 N16R8** (see below) | has Bluetooth LE + Wi-Fi (an Arduino Uno does not); ≈100,000–250,000 VND |
| WS2812B strip, **96 or 144 LEDs/m**, ≈1.25 m for 88 keys | sparser strips work but keys share LEDs: 74/m → 9 keys share, 60/m → 21, **30/m → 37 LEDs for 88 keys (1–3 keys per LED)** |
| 5 V power supply, 3–4 A | 177 LEDs at full white could draw ~10 A; the firmware caps current (`MAX_MILLIAMPS`) |
| 330 Ω resistor, 1000 µF / 16 V capacitor | from the tutorial: protect the data line and smooth the supply |

## Wiring
```
ESP32 GPIO 16 ──330 Ω──► DIN  (strip input end, arrow points away from it)
5 V supply + ─────────► 5V   (+ capacitor + leg)
5 V supply − ─────────► GND  (+ capacitor − leg) ──► ESP32 GND   ← grounds must be joined
```
Power the ESP32 by USB, or from the same 5 V supply into its 5V/VIN pin.

## Setup (Arduino IDE)
1. Boards Manager → install **esp32** by Espressif. Library Manager → install **FastLED**.
   ESP32-WROOM-32: Tools → Board → **ESP32 Dev Module**, choose its USB port (install the CP2102 or
   CH340 driver if the port doesn't show). If the upload waits at "Connecting…", hold the **BOOT** button.
   **Tools → Partition Scheme → "Minimal SPIFFS (1.9MB APP with OTA)"** (Bluetooth + Wi-Fi make the
   program big). If it says "Sketch too big", choose "Huge APP (3MB No OTA/1MB SPIFFS)"; then the web
   update does not work, but everything else does.
2. Open `LedPianoStrip/LedPianoStrip.ino`. Normally you only check `LED_PIN`; the app sends the rest.

| Setting | Example | Meaning |
|---|---|---|
| `LED_PIN` | 16 | GPIO wired to DIN (WROOM-32: avoid 0, 2, 12, 15 and 6–11) |
| `MAX_LEDS` | 300 | the most LEDs it can drive (the real count comes from the app) |
| `DEF_NUM_LEDS` … `DEF_KEYS` | 177, 144, 21, 88 | used until the app has sent its settings once |
| `DEF_MILLIAMPS` | 2000 | your supply's limit (also on the web page) |
| `AP_PASSWORD` | ledpiano | password of the ESP32's own Wi-Fi |

3. Upload. A blue dot runs along the keys once (self-test), then the strip goes dark.
4. In the app: the board shows at the top of the LED list as **"LED Piano Strip · ESP32-WROOM-32 · WS2812B"**
   — tap it (Python: or pick its **USB** port).
   Then Cài đặt → Dải WS2812B: strip density, number of piano keys, direction; and "Căn đèn LED":
   number of LEDs (− / +) and ◀ ▶ until the light sits on middle C.

## Choosing the board (top of `LedPianoStrip.ino`)
```cpp
#define BOARD_AUTO                       // from Tools → Board (default)
// #define BOARD_ESP32_WROOM_32
// #define BOARD_ESP32_S3_DEVKITC_N16R8
// #define BOARD_ESP32_C3_SUPERMINI
```
`BOARD_AUTO` picks the S3 settings when Tools → Board is "ESP32S3 Dev Module" and the C3 settings when it is
"ESP32C3 Dev Module". Choosing `BOARD_ESP32_S3_DEVKITC_N16R8` or `BOARD_ESP32_C3_SUPERMINI` explicitly stops the build with a clear message if another board is selected,
and warns when PSRAM is off.

| | ESP32-WROOM-32 | **ESP32-S3-DevKitC N16R8** | **ESP32-C3 Super Mini** |
|---|---|---|---|
| Bluetooth name | LED Piano Strip | **LED Piano Strip S3** | **LED Piano Strip C3** |
| Notes per song | 3,000 | **20,000** (in the 8 MB PSRAM) | 3,000 |
| Songs kept on the board | 6 | **30** | 6 |
| LEDs | 300 | **1,000** | 300 |
| Strip data pin | GPIO16 | GPIO16 | **GPIO10** |
| BOOT button | GPIO0 | GPIO0 | **GPIO9** |
| USB ports for the apps | 1 | **2** (USB + COM) | 1 (its own USB) |
| Status light | – | board RGB LED | blue LED (GPIO8) |

With arduino-cli: `arduino-cli compile --profile esp32s3_n16r8 --upload -p <port>` (or `esp32_wroom32`,
`esp32c3_supermini`; profiles in `sketch.yaml`).

## Song library (both boards)
Every song sent from the app ("Gửi bài này vào ESP32") is **added** to the board's library with its title,
instead of replacing the last one; when it is full the oldest goes.
- **BOOT ×1**: start the current song / skip to the next chord. **BOOT ×2 (quickly)**: next song — its number
  shows as that many blue LEDs. **Hold BOOT 1 s**: stop.
- Web page (http://ledpiano.local): the list, ▶ to choose and start a song, ✕ to delete it.
- The apps read the board's capacity from its STATUS message (bytes 7–8), so on an S3 they send up to 20,000 notes.
- New message `09 SONG_TITLE` (UTF-8, ≤ 60 bytes) just before `06 SONG_BEGIN`; older firmware ignores it.
- A song longer than the board holds keeps its beginning.

## ESP32-S3-DevKitC-1 N16R8
Same firmware, same apps. The board advertises itself as **"LED Piano Strip S3"** and the apps show it as
**ESP32-S3-DevKitC N16R8** (Bluetooth list, USB list and Wi-Fi list).

**Arduino IDE settings** (Tools menu):

| Setting | Value |
|---|---|
| Board | **ESP32S3 Dev Module** |
| Flash Size | 16MB (128Mb) |
| PSRAM | OPI PSRAM (the "R8" = 8 MB octal PSRAM) |
| Partition Scheme | **8M with spiffs (3MB APP/1.5MB SPIFFS)** – room for Bluetooth + Wi-Fi + web update, and keeps the stored song |
| USB CDC On Boot | **Enabled** – then *both* USB-C ports talk to the apps |
| Upload Mode | UART0 / Hardware CDC (default) |

**The two USB-C ports**

| Port label | Chip | Shows up as | Use |
|---|---|---|---|
| **USB** | the S3's own USB (303A:1001) | Android "USB · ESP32-S3 (cổng USB)", Mac `cu.usbmodem…` | apps, upload |
| **COM** / UART | CP2102N (v1.0) or CH343 (v1.1) | Android "USB · CP210x …" / "CH343 …", Mac `cu.SLAB…` / `cu.wchusbserial…` | apps, upload, Serial Monitor |

If an upload hangs at "Connecting…": hold **BOOT**, tap **RST**, let go of BOOT.

**Wiring:** strip DIN ← 330 Ω ← **GPIO16** (fine on the S3). Do not use GPIO 0/3/45/46 (boot pins), 19/20 (USB),
26–32 (flash), 35–37 (PSRAM), 43/44 (COM port) or the board LED pin (48 on v1.0, 38 on v1.1).
The board's 5V pin can power the ESP32 from the strip's 5 V supply; join all grounds.

**Board RGB LED:** dim green = ready, blue = an app is connected, purple = Bluetooth piano connected. On a
v1.1 board change `BOARD_RGB_PIN` to 38 (or −1 to switch it off).

Bluetooth: the S3 has Bluetooth LE 5 (no Bluetooth Classic) — everything here uses LE, so all features work,
including the Bluetooth MIDI piano and Synthesia. The STATUS message sets bit 3 on an S3.

## ESP32-C3 Super Mini (4 MB)
The smallest board: Wi-Fi + Bluetooth LE 5, one USB-C port. Advertises as **"LED Piano Strip C3"**; the apps
show it as **ESP32-C3 Super Mini**. Everything works (apps over Bluetooth / USB / Wi-Fi, Bluetooth MIDI piano,
Synthesia, song library, web page, WLED/Art-Net/E1.31/DDP), with the same limits as the WROOM-32.

**Arduino IDE settings:** Board **ESP32C3 Dev Module** · Flash Size **4MB** · Partition Scheme
**Minimal SPIFFS (1.9MB APP with OTA/190KB SPIFFS)** · USB CDC On Boot **Enabled** (without it the apps
and Serial Monitor hear nothing on the USB port).

**First upload:** if it hangs at "Connecting…" or no port appears: hold **BOOT**, press and release **RESET**,
let go of BOOT, then upload. Afterwards press RESET once.

**Wiring:** strip DIN ← 330 Ω ← **GPIO10**. Pins on the board are 0–10, 20, 21; do not use 2, 8, 9 (boot pins,
blue LED, BOOT button), 18/19 (USB) or 20/21 (UART, free for a USB-serial adapter that the apps can also use).
The board's **5V** pin can take the strip's 5 V supply; join all grounds. The C3 gives 3.3 V data — most
WS2812B strips accept it with a short wire; if the first LED flickers, add a 74AHCT125 level shifter.

**Blue LED:** short blink every 2 s = ready · on = app or piano connected · on with a blink each second = both.

**Wi-Fi:** many Super Mini boards cannot join Wi-Fi at full power (poor antenna tuning), so the firmware uses
8.5 dBm (`WIFI_TX_POWER`). Remove that line for more range if your board is fine. The STATUS message sets bit 4 on a C3.

## All the ways to connect the apps to a WS2812B strip

| # | Way | Hardware | Android | iPhone/iPad | Mac | Python | One LED per key | ESP32 extras* |
|---|---|---|---|---|---|---|---|---|
| 1 | **Bluetooth LE** | ESP32 + this firmware | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2 | **USB cable** | ESP32 + this firmware | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| 3 | **Wi-Fi (TCP 7777)** – new | ESP32 + this firmware | ✅ found by itself | ✅ type IP or `ledpiano.local` | ✅ found by itself | ✅ found by itself | ✅ | ✅ |
| 4 | **WLED** (UDP 21324) | any WLED controller (or this firmware) | ✅ | ✅ | ✅ | ✅ | ✅ | – |
| 5 | **Art-Net** (UDP 6454) | BanlanX SP801E, Art-Net nodes (or this firmware) | ✅ | ✅ | ✅ | ✅ | ✅ | – |
| 6 | **E1.31 / sACN** (UDP 5568) – new | WLED, ESPixelStick, Falcon, most Wi-Fi pixel controllers (or this firmware) | ✅ | ✅ | ✅ | ✅ | ✅ | – |
| 7 | **DDP** (UDP 4048) – new | WLED, ESPixelStick, xLights controllers (or this firmware) | ✅ | ✅ | ✅ | ✅ | ✅ | – |
| 8 | **Adalight on USB** – new | **Arduino Uno / Nano** + `LedPianoAdalight.ino` | ✅ | ❌ | ✅ | ✅ | ✅ | – |
| 9 | **Adalight on Bluetooth Classic** – new | Arduino + **HC-05 / HC-06** module | ✅ | ❌ (iOS: BLE only) | ✅ | ✅ (Windows/Mac) | ✅ | – |
| 10 | BanlanX SP630E / SP802E | ready-made Bluetooth controller | ✅ | ✅ | ✅ | ✅ | ❌ whole strip one colour | – |
| 11 | BanlanX SP611E | ready-made Bluetooth controller | ✅ | ✅ | ✅ | ✅ | ❌ 16 zones | – |

\* ESP32 extras: store songs for practice without the phone, Bluetooth MIDI piano through the ESP32,
instant green/red flashes, status.

**Which one?** ESP32 + this firmware gives everything (ways 1–3). No programming at all: a WLED box
(ways 4, 6, 7). You already own an Arduino Uno/Nano from the FastLED tutorial: way 8 (cable) or 9 (HC-05).

### Arduino Uno / Nano (Adalight) – `LedPianoAdalight/LedPianoAdalight.ino`
1. Library Manager → FastLED. Board: Arduino Uno (or Nano). Set `LED_PIN` (7 like the tutorial) and `NUM_LEDS`.
2. Upload. LEDs 0, 1, 2 flash red, green, blue (self-test).
3. In the app: Cài đặt → kiểu kết nối **"Arduino Uno/Nano – Adalight"**, then:
   Android – plug in by USB-OTG (a genuine Arduino connects by itself; a clone with CH340 shows as
   **"Arduino (Adalight) · USB · CH340"**), or "Cách khác" → **Arduino – Bluetooth HC-05**;
   Mac – Kết nối → **Arduino Uno/Nano (Adalight)**; Python – "Cách khác…" → Arduino.
4. HC-05 / HC-06: pair it in the phone's / computer's Bluetooth settings (PIN 1234 or 0000) and set it to
   115200 baud once (`AT+UART=115200,0,0` for HC-05, `AT+BAUD8` for HC-06). Wiring: HC-05 TXD → D0 (RX),
   HC-05 RXD ← D1 (TX) through a 1 kΩ/2 kΩ divider. Unplug it while uploading.

Adalight packet: `'A' 'd' 'a' countHi countLo (countHi ^ countLo ^ 0x55)` then (count + 1) × r g b.
Speed: 177 LEDs at 115200 baud ≈ 46 ms per picture — fine for lessons.

### ESP32 over Wi-Fi (TCP 7777)
Same messages as Bluetooth, both ways. Boards are found automatically in two ways:
- the board advertises the Bonjour / mDNS service **`_ledpiano._tcp`** (port 7777, TXT `board=…`, name = the
  Bluetooth name) — this is how iPhone/iPad find it;
- the apps broadcast `LEDPIANO?` on UDP 7778 and every board answers `LEDPIANO <name>` (Android, Mac, Python;
  older firmware only).

The apps also find **WLED** controllers by their Bonjour service `_wled._tcp`, so WLED needs no typed IP either.
One app at a time on TCP 7777; a new connection replaces the old one.

## Connecting: USB cable or Bluetooth
Same two links as FriendBot (USB serial + Bluetooth); the same messages go over both.

| App | USB cable | Bluetooth |
|---|---|---|
| **Android** | ✅ USB-C / OTG cable. Plug in → Android asks "Open LED Piano?" → OK: it connects by itself. Or "Kết nối" → **USB · CP210x / CH340 / ESP32** at the top of the list. | ✅ |
| **Mac** | ✅ "Kết nối" → **USB (cáp)** → pick the port (`cu.usbserial…`, `cu.SLAB_USBtoUART`, `cu.wchusbserial…`, `cu.usbmodem…`). Close Arduino IDE's Serial Monitor first — only one program can use the port. | ✅ |
| **iPhone / iPad** | ❌ iOS does not let apps open USB serial chips | ✅ (use this) |
| **Python (Windows/Mac/Linux)** | ✅ pick the **USB** port | ✅ |

USB chips the apps know: ESP32-S3/C3 native USB (Espressif 303A), CP2102/CP2104 (10C4:EA60), CH340 (1A86:7523),
CH343/CH9102 (1A86:55D3/55D4). 115200 baud, 8N1. The apps keep **DTR and RTS off**: on dev boards these lines
drive EN (reset) and GPIO0 (BOOT = the practice button). Opening the port can still restart the board, so the
apps wait 1.5 s before sending. Use a cable that carries data (some phone cables only charge).
ESP32-S3 with its own USB port: Tools → **USB CDC On Boot: Enabled**.

## Colours
With the apps: the hand colours from the app settings (black keys a little dimmer). Led Organ mode, Synthesia and practising without the phone use the colours the app sent last (default blue / orange).

## Messages (Bluetooth service 8f1b0001-…, write 8f1b0002-…, or USB serial 115200)
```
A5 op len payload… checksum        checksum = op ^ len ^ every payload byte
op 01 PIXELS      n × (index low, index high, r, g, b)   all LEDs off, these on, show
op 02 BRIGHTNESS  1 byte
op 03 FILL        r g b
op 04 CONFIG      LEDs(2) density lowest keys firstLed(2, signed) reversed R G B (right) R G B (left)
op 05 EXPECT      the keys (MIDI numbers) the learner should press now
op 06/07/08       store a song: BEGIN count(2) · DATA index(2) + ≤49 × (ms 3 bytes, pitch, hand 0 R / 1 L) · END count(2)
ESP32 → app (notify 8f1b0003-…, or USB serial once the app has written there):
op 10 NOTE        pitch velocity (0 = released) from the Bluetooth MIDI piano
op 11 STATUS      flags (bit0 piano, bit1 home Wi-Fi, bit2 song stored, bit3 ESP32-S3, bit4 ESP32-C3), IPv4 (4 bytes), song notes(2),
                  notes per song the board holds (2)
op 09 SONG_TITLE  (app → ESP32) song name, UTF-8, before SONG_BEGIN
```
Example — `leds[0] = CRGB(255,0,0)` and `leds[300] = CRGB(0,0,255)`:
`A5 01 0A 00 00 FF 00 00 2C 01 00 00 FF 26`. All off: `A5 01 00 01`.

## Test (on a PC)
* `g++ -std=c++17 test/test_keymap.cpp -o t && ./t` – message decoding and the key→LED map.
* `g++ -std=c++17 test/test_piano.cpp -o t && ./t` – Bluetooth MIDI decoding, the settings, song storage and the
  practice lesson, using **bytes made by the Python app** (`test/vectors.h`), so app and firmware must agree.
* Syntax check of the sketch: `cp LedPianoStrip/LedPianoStrip.ino /tmp/x.cpp && sed -i '1i #include "Arduino.h"' /tmp/x.cpp &&
  g++ -std=gnu++17 -fsyntax-only -Itest/arduino_stubs -ILedPianoStrip /tmp/x.cpp` (stand-in headers, not the real ESP32 ones).
