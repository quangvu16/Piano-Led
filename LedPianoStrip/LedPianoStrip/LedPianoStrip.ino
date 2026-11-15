// LED Piano Strip 2 - ESP32 + WS2812B firmware for the LED Piano apps (Android, iPhone/iPad, Mac, Python).
//
// What it does
//  * App mode: the app sets each LED ("A5 op len … chk" messages, like `leds[i] = CRGB(r, g, b)` in the
//    FastLED example) or sends Led Organ key messages (7F key state … 6E). Bluetooth LE or USB serial.
//  * Bluetooth MIDI piano: the ESP32 connects to a digital piano with Bluetooth MIDI by itself, sends
//    the notes to the app (so the phone needs only ONE Bluetooth link) and flashes the key green/red
//    at once when the app has told it which keys are expected.
//  * Synthesia: shows up as a Bluetooth MIDI device "LED Piano Strip"; choose it as the Key Lights
//    output (channels 1–5 = left hand colour, the others = right hand colour).
//  * Practise without the phone: the app can store a song on the ESP32. Press BOOT to start; the next
//    chord lights up and waits for the right keys (short press = skip, long press = stop).
//  * Wi-Fi: web page at http://ledpiano.local (or the IP shown on Serial) for settings and firmware
//    update (OTA). The apps can connect over Wi-Fi with every feature (TCP port 7777, the same messages as
//    Bluetooth; they find the board with a "LEDPIANO?" broadcast on UDP 7778). It also receives the
//    standard pixel protocols: WLED realtime (UDP 21324), Art-Net (6454), E1.31 / sACN (5568) and DDP (4048).
//  * Free play: with a Bluetooth MIDI piano and no app, the keys you play light up (choose the effect on
//    the web page). Auto-off after a few idle minutes, and a current limit for your power supply.
//
// Based on the FastLED example from HowToMechatronics:
// https://howtomechatronics.com/tutorials/arduino/how-to-control-ws2812b-individually-addressable-leds-using-arduino/
//
// Boards:     ESP32-WROOM-32 dev board: Tools → Board → "ESP32 Dev Module",
//               Partition Scheme → "Minimal SPIFFS (1.9MB APP with OTA)".
//             ESP32-S3-DevKitC-1 N16R8 (16 MB flash, 8 MB PSRAM): Tools → Board → "ESP32S3 Dev Module",
//               Flash Size "16MB (128Mb)", PSRAM "OPI PSRAM", Partition Scheme "8M with spiffs (3MB APP/1.5MB SPIFFS)",
//               USB CDC On Boot "Enabled". Upload through either USB-C port. Both ports then talk to the apps:
//               "USB" (the S3's own USB) and "COM"/"UART" (CP2102N or CH343 chip). Bluetooth name "LED Piano Strip S3".
//               The board's RGB LED shows the state: green = ready, blue = app connected, purple = piano too.
//             ESP32-C3 Super Mini (4 MB flash, Wi-Fi + Bluetooth LE 5): Tools → Board → "ESP32C3 Dev Module",
//               Flash Size "4MB", Partition Scheme "Minimal SPIFFS (1.9MB APP with OTA/190KB SPIFFS)",
//               USB CDC On Boot "Enabled" (its only USB-C port is the chip's own USB). Bluetooth name
//               "LED Piano Strip C3". Strip on GPIO 10, BOOT button = GPIO 9, blue LED (GPIO 8) shows the state.
//               First upload: hold BOOT, press and release RESET, release BOOT.
//             Any other ESP32 with Bluetooth LE + Wi-Fi works the same way.
// Library:    FastLED (Library Manager). Everything else comes with the "esp32" board package.
// Wiring:     strip DIN  <- 330 Ω resistor <- GPIO LED_PIN
//             strip 5V   <- external 5 V supply (+), 1000 µF capacitor across 5V/GND at the strip
//             strip GND  <- supply (−) AND ESP32 GND (grounds MUST be joined)

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLEScan.h>
#include <BLEClient.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <Update.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <FastLED.h>
#include "KeyMap.h"
#include "Piano.h"

using namespace ledpiano;

// ======================= 1. CHOOSE YOUR BOARD (keep ONE line) =======================
#define BOARD_AUTO                       // from Tools → Board: "ESP32S3 Dev Module" → S3, "ESP32C3 Dev Module" → C3, else WROOM-32
// #define BOARD_ESP32_WROOM_32          // ESP32-WROOM-32 dev board ("ESP32 Dev Module")
// #define BOARD_ESP32_S3_DEVKITC_N16R8  // ESP32-S3-DevKitC-1 N16R8 (16 MB flash, 8 MB PSRAM)
// #define BOARD_ESP32_C3_SUPERMINI      // ESP32-C3 Super Mini (4 MB flash, Wi-Fi + Bluetooth LE)
// =====================================================================================

#if defined(BOARD_ESP32_C3_SUPERMINI) || (defined(BOARD_AUTO) && CONFIG_IDF_TARGET_ESP32C3)
  #if !CONFIG_IDF_TARGET_ESP32C3
    #error "BOARD_ESP32_C3_SUPERMINI needs Tools → Board → \"ESP32C3 Dev Module\""
  #endif
  // ---- ESP32-C3 Super Mini (4 MB) ----
  // Arduino IDE: Board "ESP32C3 Dev Module", Flash Size "4MB", Partition Scheme
  // "Minimal SPIFFS (1.9MB APP with OTA/190KB SPIFFS)", USB CDC On Boot "Enabled".
  // Pins on the board: 0–10, 20, 21. No PSRAM and no Bluetooth Classic (Bluetooth LE only — all the
  // firmware needs: the apps, Synthesia and Bluetooth MIDI pianos all use Bluetooth LE).
  #define IS_S3          0
  #define IS_C3          1
  #define BOARD_LABEL    "ESP32-C3 Super Mini"
  #define DEVICE_NAME    "LED Piano Strip C3"  // the apps show it as "ESP32-C3 Super Mini"
  #define LED_PIN        10    // strip DIN (via 330 Ω). Avoid 2, 8, 9 (boot / blue LED / BOOT button),
                               // 18/19 (USB), 20/21 (UART RX/TX)
  #define BOARD_RGB_PIN  -1
  #define BOARD_LED_PIN  8     // the small blue LED, lit when the pin is LOW
  #define BOARD_BUTTON   9     // BOOT button on the C3
  #define MAX_LEDS       300
  #define SONG_NOTES_MAX 3000
  #define LIBRARY_MAX    6     // songs kept in flash (190 KB)
  // Many Super Mini boards have a poorly tuned antenna and cannot join Wi-Fi at full power;
  // 8.5 dBm fixes it (reach is still fine inside a room). Comment out to use full power.
  #define WIFI_TX_POWER  WIFI_POWER_8_5dBm
#elif defined(BOARD_ESP32_S3_DEVKITC_N16R8) || (defined(BOARD_AUTO) && CONFIG_IDF_TARGET_ESP32S3)
  #if !CONFIG_IDF_TARGET_ESP32S3
    #error "BOARD_ESP32_S3_DEVKITC_N16R8 needs Tools → Board → \"ESP32S3 Dev Module\""
  #endif
  // ---- ESP32-S3-DevKitC-1 N16R8 ----
  // Arduino IDE: Board "ESP32S3 Dev Module", Flash Size "16MB (128Mb)", PSRAM "OPI PSRAM",
  // Partition Scheme "8M with spiffs (3MB APP/1.5MB SPIFFS)", USB CDC On Boot "Enabled".
  #define IS_S3          1
  #define IS_C3          0
  #define BOARD_LABEL    "ESP32-S3-DevKitC N16R8"
  #define DEVICE_NAME    "LED Piano Strip S3"  // the apps show it as "ESP32-S3-DevKitC N16R8"
  #define LED_PIN        4     // strip DIN (via 330 Ω). Avoid 0, 3, 45, 46 (boot), 19/20 (USB), 26–32 (flash),
                               // 35–37 (PSRAM), 43/44 (COM port), 38/48 (board RGB LED)
  #define BOARD_RGB_PIN  48    // the board's own RGB LED: 48 on DevKitC-1 v1.0, 38 on v1.1; -1 = not used
  #define MAX_LEDS       1000  // up to ≈ 6.5 m of 144/m strip
  #define SONG_NOTES_MAX 20000 // per song, kept in the 8 MB PSRAM (a whole sonata)
  #define LIBRARY_MAX    30    // songs kept in flash (1.5 MB); the oldest goes when it is full
  #if !defined(BOARD_HAS_PSRAM)
    #warning "PSRAM is off (Tools → PSRAM → OPI PSRAM): songs are limited to 3000 notes"
  #endif
#else
  // ---- ESP32-WROOM-32 ----
  // Arduino IDE: Board "ESP32 Dev Module", Partition Scheme "Minimal SPIFFS (1.9MB APP with OTA)".
  #define IS_S3          0
  #define IS_C3          0
  #define BOARD_LABEL    "ESP32-WROOM-32"
  #define DEVICE_NAME    "LED Piano Strip"
  #define LED_PIN        16    // strip DIN (via 330 Ω), "RX2". Avoid 0, 2, 12, 15, 6–11.
  #define BOARD_RGB_PIN  -1
  #define MAX_LEDS       300
  #define SONG_NOTES_MAX 3000
  #define LIBRARY_MAX    6     // songs kept in flash (190 KB)
#endif

// ======================= 2. CHANGE THESE IF YOU NEED TO ================================
// The apps set strip density, piano size, LED count, alignment, direction and colours over Bluetooth
// (and the ESP32 remembers them), so normally you only check LED_PIN above.
#ifdef BOARD_BUTTON
#define BUTTON_PIN     BOARD_BUTTON
#else
#define BUTTON_PIN     0          // the BOOT button on the board
#endif
#define AP_PASSWORD    "ledpiano" // password of the ESP32's own Wi-Fi "LED-Piano-XXXX" (8+ characters)
// Defaults until the app sends its settings:
#define DEF_NUM_LEDS   60
#define DEF_DENSITY    144
#define DEF_LOWEST     21         // 88 keys = A0 (21); 76 keys = E1 (28); 61 / 49 keys = C2 (36)
#define DEF_KEYS       88
#define DEF_BRIGHTNESS 90         // 0..255
#define DEF_MILLIAMPS  2000       // your 5 V supply's limit in mA (FastLED dims to stay below it)
#define DEF_AUTO_OFF   10         // minutes without any activity → LEDs off (0 = never)
// ===================================================================================

#define NUS_SERVICE "6e400001-b5a3-f393-e0a9-e50e24dcca9e"  // Led Organ compatible
#define NUS_RX      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define NUS_TX      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define WS_SERVICE  "8f1b0001-6c3e-4c1a-9f2e-5a1e0d2c7701"  // LED Piano: the apps pick "WS2812B" mode from it
#define WS_WRITE    "8f1b0002-6c3e-4c1a-9f2e-5a1e0d2c7701"
#define WS_NOTIFY   "8f1b0003-6c3e-4c1a-9f2e-5a1e0d2c7701"  // ESP32 → app: piano notes, status
#define MIDI_SERVICE "03b80e5a-ede8-4b33-a751-6ce34ec4c700" // Bluetooth LE MIDI (piano, Synthesia)
#define MIDI_CHAR    "7772e5db-3868-4112-a1a9-f2669d106bf3"

// ---------------------------------------------------------------- state
CRGB leds[MAX_LEDS];
CRGB rt[MAX_LEDS];            // WLED / Art-Net picture
StripConfig cfg;
Colours colours;
KeyMap keymap;
Preferences prefs;
SongBook book;
Lesson lesson;
KeySet expected;              // keys the app says should be pressed now
BleMidiParser pianoMidi, synthMidi;

uint8_t brightness = DEF_BRIGHTNESS;
int milliamps = DEF_MILLIAMPS, autoOffMin = DEF_AUTO_OFF, effect = 1, artStart = 0;
String webPassword;

// What the app last asked for
enum AppKind : uint8_t { APP_NONE, APP_KEYS, APP_PIXELS, APP_FILL };
AppKind appKind = APP_NONE;
LitKey appKeys[MAX_LIT];
int appKeyCount = 0;
Pixel appPixels[MAX_PIXELS];
int appPixelCount = 0;
CRGB appFill;

// Synthesia key lights: pitch → 0 off, 1 right, 2 left
uint8_t synthKeys[128];
// Free play: velocity of held keys, time of release (fade-out)
uint8_t heldVel[128];
uint32_t releasedAt[128];
uint8_t releasedVel[128];

struct Flash { int16_t led; CRGB c; uint32_t until; };
Flash flashes[16];

uint32_t rtUntil = 0, lastActivity = 0, lastStatus = 0;
bool dirty = true, asleep = false, serialApp = false, tcpApp = false, uartApp = false;
volatile bool appConnected = false;
volatile bool pianoConnected = false;

BLEServer* server = nullptr;
BLECharacteristic* wsNotify = nullptr;
BLECharacteristic* midiChar = nullptr;
WebServer web(80);
WiFiUDP udpWled, udpArt, udpE131, udpDdp, udpFind;
WiFiServer tcpServer(7777);  // the apps over Wi-Fi: same "A5 …" messages as Bluetooth / USB, both ways
WiFiClient tcpClient;
bool wifiStarted = false, staMode = false;

// ---------------------------------------------------------------- Bluetooth → loop() hand-over
// Bluetooth callbacks run in another task; they only copy the bytes into this queue and loop()
// does all the work, so nothing is touched from two tasks at once.
enum Source : uint8_t { SRC_NUS, SRC_WS, SRC_PIANO, SRC_SYNTH, SRC_DISCONNECT };
struct Chunk { uint8_t src; uint8_t len; uint8_t d[200]; };
QueueHandle_t rxQueue;

static void enqueue(uint8_t src, const uint8_t* p, size_t n) {
  do {
    Chunk c;
    c.src = src;
    c.len = n > sizeof(c.d) ? sizeof(c.d) : n;
    memcpy(c.d, p, c.len);
    xQueueSend(rxQueue, &c, 0);  // full queue: drop (a newer picture follows)
    p += c.len;
    n -= c.len;
  } while (n > 0);
}

class RxCallbacks : public BLECharacteristicCallbacks {
 public:
  explicit RxCallbacks(uint8_t src) : src_(src) {}
  void onWrite(BLECharacteristic* c) override {
    auto v = c->getValue();  // std::string (core 2.x) or String (core 3.x)
    if (v.length()) enqueue(src_, (const uint8_t*)v.c_str(), v.length());
  }
 private:
  uint8_t src_;
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    appConnected = true;
    if (s->getConnectedCount() < 3) BLEDevice::startAdvertising();  // Synthesia and the app can both connect
  }
  void onDisconnect(BLEServer* s) override {
    uint8_t none = 0;
    enqueue(SRC_DISCONNECT, &none, 1);
    BLEDevice::startAdvertising();
  }
};

// ---------------------------------------------------------------- Bluetooth MIDI piano (ESP32 = client)
BLEClient* pianoClient = nullptr;
BLEAdvertisedDevice* pianoFound = nullptr;
bool scanning = false;
uint32_t scanEnds = 0, nextScan = 3000;
int emptyScans = 0;

class ScanCallbacks : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice d) override {
    if (pianoFound || !d.haveServiceUUID() || !d.isAdvertisingService(BLEUUID(MIDI_SERVICE))) return;
    if (d.haveName() && String(d.getName().c_str()).startsWith("LED Piano")) return;  // not another strip
    pianoFound = new BLEAdvertisedDevice(d);
  }
};
ScanCallbacks scanCallbacks;

class PianoClientCallbacks : public BLEClientCallbacks {
  void onConnect(BLEClient*) override {}
  void onDisconnect(BLEClient*) override { pianoConnected = false; }
};

static void pianoNotify(BLERemoteCharacteristic*, uint8_t* data, size_t len, bool) { enqueue(SRC_PIANO, data, len); }

static void connectPiano() {
  BLEAdvertisedDevice* dev = pianoFound;
  pianoFound = nullptr;
  if (!pianoClient) {
    pianoClient = BLEDevice::createClient();
    pianoClient->setClientCallbacks(new PianoClientCallbacks());
  }
  Serial.printf("Piano found: %s – connecting\n", dev->getName().c_str());
  if (pianoClient->connect(dev)) {
    BLERemoteService* svc = pianoClient->getService(BLEUUID(MIDI_SERVICE));
    BLERemoteCharacteristic* ch = svc ? svc->getCharacteristic(BLEUUID(MIDI_CHAR)) : nullptr;
    if (ch && ch->canNotify()) {
      ch->registerForNotify(pianoNotify);
      pianoConnected = true;
      Serial.println("Piano connected");
    } else {
      pianoClient->disconnect();
    }
  }
  delete dev;
}

static void pollPiano(uint32_t now) {
  if (pianoConnected) return;
  if (pianoFound) {
    if (scanning) { BLEDevice::getScan()->stop(); scanning = false; }
    connectPiano();
    emptyScans = 0;
    nextScan = now + 8000;
    return;
  }
  if (scanning && (int32_t)(now - scanEnds) >= 0) {
    scanning = false;
    BLEDevice::getScan()->clearResults();
    // Look again every 10 s while no piano is connected; after 2 minutes only once a minute, so the
    // Bluetooth radio stays free for the app.
    emptyScans++;
    nextScan = now + (emptyScans > 12 ? 60000 : 10000);
  }
  if (!scanning && (int32_t)(now - nextScan) >= 0) {
    BLEScan* s = BLEDevice::getScan();
    s->setAdvertisedDeviceCallbacks(&scanCallbacks, false);
    s->setActiveScan(true);
    s->setInterval(160);
    s->setWindow(80);
    s->start(3, nullptr, false);  // non-blocking, 3 s
    scanning = true;
    scanEnds = now + 3300;
  }
}

// ---------------------------------------------------------------- colours and helpers
static CRGB rgb(const uint8_t* c) { return CRGB(c[0], c[1], c[2]); }

static CRGB handColour(bool left, bool black) {
  CRGB c = rgb(left ? colours.left : colours.right);
  if (black) c.nscale8_video(153);  // black keys 60 %, like the apps
  return c;
}

static CRGB keyStateColour(uint8_t state) {  // Led Organ states 1..4
  return handColour(state >= 3, state == 2 || state == 4);
}

static int numLeds() { return cfg.numLeds > MAX_LEDS ? MAX_LEDS : cfg.numLeds; }

static void flashLed(int led, CRGB c, uint16_t ms) {
  if (led < 0) return;
  int slot = 0;
  for (int i = 0; i < 16; i++) {
    if (flashes[i].led == led) { slot = i; break; }
    if ((int32_t)(flashes[i].until - flashes[slot].until) < 0) slot = i;  // the oldest
  }
  flashes[slot] = Flash{(int16_t)led, c, millis() + ms};
  dirty = true;
}

static void activity() {
  lastActivity = millis();
  if (asleep) { asleep = false; dirty = true; }
}

static void sendToApp(uint8_t op, const uint8_t* payload, uint8_t len) {
  uint8_t msg[40];
  int n = buildMessage(op, payload, len, msg);
  if (wsNotify && server && server->getConnectedCount() > 0) {
    wsNotify->setValue(msg, n);
    wsNotify->notify();
  }
  if (serialApp) Serial.write(msg, n);
#if ARDUINO_USB_CDC_ON_BOOT
  if (uartApp) Serial0.write(msg, n);  // ESP32-S3: the app on the other USB-C port ("COM")
#endif
  if (tcpClient && tcpClient.connected()) tcpClient.write(msg, n);
}

// ---------------------------------------------------------------- settings
static void applyConfig() {
  keymap.build(cfg);
  dirty = true;
}

static void loadSettings() {
  prefs.begin("ledpiano", false);
  cfg.numLeds = DEF_NUM_LEDS;
  cfg.ledsPerMetre = DEF_DENSITY;
  cfg.lowestPitch = DEF_LOWEST;
  cfg.numKeys = DEF_KEYS;
  uint8_t blob[14];
  if (prefs.getBytes("cfg", blob, sizeof blob) == sizeof blob) parseConfig(blob, sizeof blob, MAX_LEDS, cfg, colours);
  brightness = prefs.getUChar("bright", DEF_BRIGHTNESS);
  milliamps = prefs.getInt("ma", DEF_MILLIAMPS);
  autoOffMin = prefs.getInt("autooff", DEF_AUTO_OFF);
  effect = prefs.getInt("effect", 1);
  artStart = prefs.getInt("artuni", 0);
  webPassword = prefs.getString("webpw", "");
  applyConfig();
}

static void saveConfigBlob(const uint8_t* d, int len) {
  uint8_t old[14];
  if (prefs.getBytes("cfg", old, sizeof old) == sizeof old && memcmp(old, d, 14) == 0) return;  // spare the flash
  prefs.putBytes("cfg", d, 14);
}

// ---------------------------------------------------------------- song library (LittleFS)
// Every song the app sends is kept as /songs/<number>.bin (up to LIBRARY_MAX; the oldest goes first).
// BOOT double-press = next song; the web page lists them. The current one is remembered.
static int libIds[64];
static int libCount = 0;
static int libCurrent = -1;  // id of the song in `book`

static String songPath(int id) { return "/songs/" + String(id) + ".bin"; }

static void libScan() {
  libCount = 0;
  File dir = LittleFS.open("/songs");
  if (!dir || !dir.isDirectory()) return;
  for (File f = dir.openNextFile(); f && libCount < 64; f = dir.openNextFile()) {
    String n = f.name();  // "12.bin" (core 2.x may give "/songs/12.bin")
    int slash = n.lastIndexOf('/');
    if (slash >= 0) n = n.substring(slash + 1);
    if (n.endsWith(".bin")) libIds[libCount++] = n.toInt();
  }
  for (int i = 1; i < libCount; i++)  // oldest (smallest number) first
    for (int j = i; j > 0 && libIds[j] < libIds[j - 1]; j--) { int t = libIds[j]; libIds[j] = libIds[j - 1]; libIds[j - 1] = t; }
}

// Title and note count of a stored song without loading it (for the web page).
static bool libInfo(int id, char* title, int& count) {
  File f = LittleFS.open(songPath(id), "r");
  if (!f) return false;
  uint8_t head[songfile::HEADER_MAX];
  int n = f.read(head, sizeof head);
  f.close();
  return songfile::parse(head, n, title, count) > 0;
}

static bool libLoad(int id) {
  File f = LittleFS.open(songPath(id), "r");
  if (!f) return false;
  uint8_t head[songfile::HEADER_MAX];
  int n = f.read(head, sizeof head);
  char title[TITLE_MAX + 1];
  int count = 0;
  int hl = songfile::parse(head, n, title, count);
  bool ok = false;
  if (hl > 0 && count <= book.capacity) {
    f.seek(hl);
    ok = f.read((uint8_t*)book.notes, sizeof(SongNote) * count) == sizeof(SongNote) * count;
    if (ok) {
      book.count = count;
      strncpy(book.title, title, TITLE_MAX);
      book.title[TITLE_MAX] = 0;
      libCurrent = id;
      prefs.putInt("song", id);
    }
  }
  f.close();
  return ok;
}

static void libDelete(int id) {
  LittleFS.remove(songPath(id));
  if (id == libCurrent) { libCurrent = -1; book.count = 0; book.title[0] = 0; }
  libScan();
}

// Saves the song just received from the app as a new library entry.
static void libSave() {
  if (!LittleFS.exists("/songs")) LittleFS.mkdir("/songs");
  libScan();
  while (libCount >= LIBRARY_MAX) libDelete(libIds[0]);
  int id = prefs.getInt("nextsong", 1);
  uint8_t head[songfile::HEADER_MAX];
  int hl = songfile::header(book, head);
  for (int attempt = 0; attempt < 2; attempt++) {
    File f = LittleFS.open(songPath(id), "w");
    bool ok = f && f.write(head, hl) == (size_t)hl &&
              f.write((const uint8_t*)book.notes, sizeof(SongNote) * book.count) == sizeof(SongNote) * book.count;
    if (f) f.close();
    if (ok) break;
    LittleFS.remove(songPath(id));  // flash full: drop the oldest and try once more
    libScan();
    if (libCount == 0) return;
    libDelete(libIds[0]);
  }
  prefs.putInt("nextsong", id + 1);
  libCurrent = id;
  prefs.putInt("song", id);
  libScan();
}

static void libOpenAtBoot() {
  if (LittleFS.exists("/song.bin")) {  // firmware 2.0 kept one song here: move it into the library
    File f = LittleFS.open("/song.bin", "r");
    uint8_t n[2];
    if (f && f.read(n, 2) == 2) {
      int count = n[0] | (n[1] << 8);
      if (count <= book.capacity && f.read((uint8_t*)book.notes, sizeof(SongNote) * count) == sizeof(SongNote) * count) {
        book.count = count;
        book.title[0] = 0;
      }
    }
    if (f) f.close();
    LittleFS.remove("/song.bin");
    if (book.count) libSave();
  }
  libScan();
  int want = prefs.getInt("song", -1);
  if (want < 0 || !libLoad(want)) {
    if (libCount) libLoad(libIds[libCount - 1]);  // the newest
  }
}

// Position (1, 2, …) of the current song in the library, 0 if none.
static int libPosition() {
  for (int i = 0; i < libCount; i++) if (libIds[i] == libCurrent) return i + 1;
  return 0;
}

// BOOT double-press: the next song; its number lights up as that many blue LEDs.
static void libNext() {
  if (libCount == 0) return;
  int pos = libPosition();  // 0 when none → the first
  int next = libIds[pos % libCount];
  if (libLoad(next)) {
    lesson.stop();
    int shown = libPosition();
    for (int i = 0; i < shown && i < numLeds(); i++) flashLed(i * 2, CRGB(0, 60, 255), 900);
    Serial.printf("Song %d/%d: %s (%d notes)\n", shown, libCount, book.title[0] ? book.title : "(no title)", book.count);
    lastStatus = 0;
  }
}

// ---------------------------------------------------------------- piano notes
static void startLesson() {
  if (book.count == 0) {  // no song stored: short red blink
    for (int i = 0; i < 3 && i < numLeds(); i++) flashLed(i, CRGB::Red, 600);
    return;
  }
  appKind = APP_NONE;
  lesson.begin(book);
  dirty = true;
  Serial.printf("Lesson started: %d notes\n", book.count);
}

static void lessonDone() {
  for (int p = cfg.lowestPitch; p < cfg.lowestPitch + cfg.numKeys; p += 3) flashLed(keymap.ledForPitch(p), CRGB::Green, 900);
}

static void onPianoNote(uint8_t pitch, uint8_t vel) {
  activity();
  uint8_t payload[2] = {pitch, vel};
  sendToApp(OP_NOTE, payload, 2);
  int led = keymap.ledForPitch(pitch);
  if (vel) {
    heldVel[pitch] = vel;
    if (lesson.running) {
      int r = lesson.press(book, pitch);
      if (r < 0) flashLed(led, CRGB::Red, 250);
      else flashLed(led, CRGB(0, 208, 64), 180);
      if (r > 0 && !lesson.running) lessonDone();
    } else if ((appConnected || serialApp || tcpApp || uartApp) && !expected.empty()) {  // app on Bluetooth, USB or Wi-Fi
      // Instant feedback before the app even hears the note: green = expected key, red = wrong key.
      flashLed(led, expected.has(pitch) ? CRGB(0, 208, 64) : CRGB::Red, 180);
    }
  } else if (heldVel[pitch]) {
    releasedVel[pitch] = heldVel[pitch];
    releasedAt[pitch] = millis();
    heldVel[pitch] = 0;
  }
  dirty = true;
}

static void onSynthNote(uint8_t status, uint8_t pitch, uint8_t vel) {
  uint8_t type = status & 0xF0, ch = status & 0x0F;
  if (pitch > 127) return;
  if (type == 0x90 && vel) synthKeys[pitch] = ch <= 4 ? 2 : 1;  // channels 1–5 = left hand
  else if (type == 0x80 || type == 0x90) synthKeys[pitch] = 0;
  else if (type == 0xB0 && (pitch == 123 || pitch == 120)) memset(synthKeys, 0, sizeof synthKeys);  // all notes off
  activity();
  dirty = true;
}

// ---------------------------------------------------------------- messages from the app
struct Input {
  FrameParser keys;
  PixelParser px;
  bool* appFlag = nullptr;  // set when an app talks on this link (USB: serialApp, Wi-Fi: tcpApp)
  void feed(uint8_t b, bool) {
    if (px.active() || b == 0xA5) {
      uint8_t op = px.feed(b);
      if (op) { if (appFlag) *appFlag = true; handle(op); }
    } else if (keys.feed(b)) {
      appKind = APP_KEYS;
      appKeyCount = keys.count();
      for (int i = 0; i < appKeyCount; i++) appKeys[i] = keys.key(i);
      appTakesOver();
    }
  }
  void appTakesOver() {
    if (lesson.running) lesson.stop();
    rtUntil = 0;
    activity();
    dirty = true;
  }
  void handle(uint8_t op) {
    const uint8_t* d = px.data();
    int len = px.length();
    switch (op) {
      case OP_PIXELS:
        appKind = APP_PIXELS;
        appPixelCount = px.count();
        for (int i = 0; i < appPixelCount; i++) appPixels[i] = px.pixel(i);
        appTakesOver();
        break;
      case OP_FILL:
        appKind = APP_FILL;
        appFill = CRGB(px.r(), px.g(), px.b());
        appTakesOver();
        break;
      case OP_BRIGHTNESS:
        brightness = px.brightness();
        activity();
        dirty = true;
        break;
      case OP_CONFIG:
        if (parseConfig(d, len, MAX_LEDS, cfg, colours)) { saveConfigBlob(d, len); applyConfig(); }
        break;
      case OP_EXPECT:
        expected.set(d, len);
        break;
      case OP_SONG_TITLE:
      case OP_SONG_BEGIN:
      case OP_SONG_DATA:
      case OP_SONG_END:
        if (book.feed(op, d, len)) {
          lesson.stop();
          libSave();
          Serial.printf("Song stored: %s, %d notes, %d in the library (press BOOT to practise)\n",
                        book.title[0] ? book.title : "(no title)", book.count, libCount);
          for (int i = 0; i < 6 && i < numLeds(); i++) flashLed(i * 2, CRGB(0, 208, 64), 500);
          lastStatus = 0;  // tell the app now
        }
        break;
    }
  }
};
Input nusInput, wsInput, serialInput, tcpInput, uartInput;

static void handleChunk(const Chunk& c) {
  switch (c.src) {
    case SRC_NUS: for (int i = 0; i < c.len; i++) nusInput.feed(c.d[i], false); break;
    case SRC_WS: for (int i = 0; i < c.len; i++) wsInput.feed(c.d[i], false); break;
    case SRC_PIANO:
      pianoMidi.parse(c.d, c.len, [](uint8_t st, uint8_t a, uint8_t b) {
        uint8_t t = st & 0xF0;
        if (t == 0x90) onPianoNote(a, b);
        else if (t == 0x80) onPianoNote(a, 0);
      });
      break;
    case SRC_SYNTH: synthMidi.parse(c.d, c.len, onSynthNote); break;
    case SRC_DISCONNECT:
      if (server->getConnectedCount() == 0) {
        appConnected = false;
        appKind = APP_NONE;
        expected.clear();
        memset(synthKeys, 0, sizeof synthKeys);
        dirty = true;
      }
      break;
  }
}

// ---------------------------------------------------------------- drawing
static void lightPitch(int pitch, CRGB c) {
  int led = keymap.ledForPitch(pitch);
  if (led >= 0 && led < numLeds()) leds[led] = c;  // exactly one LED per key
}

// Returns true while something is still animating (fades, flashes).
static bool render(uint32_t now) {
  bool anim = false;
  int n = numLeds();
  fill_solid(leds, MAX_LEDS, CRGB::Black);
  if (asleep) {
    // auto-off: everything dark
  } else if (rtUntil && (int32_t)(rtUntil - now) > 0) {
    memcpy(leds, rt, sizeof(CRGB) * n);
  } else if (appKind != APP_NONE) {
    if (appKind == APP_KEYS) {
      for (int i = 0; i < appKeyCount; i++) {
        int led = keymap.ledForKeyByte(appKeys[i].key);
        if (led >= 0 && led < n) leds[led] = keyStateColour(appKeys[i].state);
      }
    } else if (appKind == APP_PIXELS) {
      for (int i = 0; i < appPixelCount; i++)
        if (appPixels[i].index < n) leds[appPixels[i].index] = CRGB(appPixels[i].r, appPixels[i].g, appPixels[i].b);
    } else {
      fill_solid(leds, n, appFill);
    }
  } else {
    bool synth = false;
    for (int p = 0; p < 128; p++)
      if (synthKeys[p]) { lightPitch(p, handColour(synthKeys[p] == 2, isBlack(p))); synth = true; }
    if (!synth && lesson.running) {
      for (int i = lesson.start; i < lesson.end; i++) {
        const SongNote& s = book.notes[i];
        if (lesson.waiting.has(s.pitch)) lightPitch(s.pitch, handColour(s.hand == 1, isBlack(s.pitch)));
      }
    } else if (!synth && effect > 0) {
      // Free play: the keys you play light up and fade out after you let go.
      for (int p = 0; p < 128; p++) {
        uint8_t v = heldVel[p];
        uint8_t fade = 255;
        if (!v && releasedVel[p]) {
          uint32_t age = now - releasedAt[p];
          if (age >= 400) { releasedVel[p] = 0; continue; }
          v = releasedVel[p];
          fade = 255 - age * 255 / 400;
          anim = true;
        }
        if (!v) continue;
        CRGB c;
        if (effect == 2) c = CHSV(160 - v * 160 / 127, 255, 255);  // soft = blue … loud = red
        else c = handColour(false, isBlack(p));
        c.nscale8_video(76 + v * 179 / 127);                       // louder = brighter
        c.nscale8_video(fade);
        lightPitch(p, c);
      }
    }
  }
  if (!asleep) {
    for (auto& f : flashes) {
      if (f.led < 0) continue;
      if ((int32_t)(f.until - now) <= 0) { f.led = -1; continue; }
      if (f.led < n) leds[f.led] = f.c;
      anim = true;
    }
  }
  FastLED.setBrightness(brightness);
  FastLED.show();
  return anim;
}

static void sweep(CRGB c) {
  for (int p = cfg.lowestPitch; p < cfg.lowestPitch + cfg.numKeys; p += 2) {
    fill_solid(leds, MAX_LEDS, CRGB::Black);
    lightPitch(p, c);
    FastLED.show();
    delay(8);
  }
  fill_solid(leds, MAX_LEDS, CRGB::Black);
  FastLED.show();
}

// ---------------------------------------------------------------- Wi-Fi: WLED / Art-Net receivers
static uint8_t udpBuf[1500];

static void rtStart(uint32_t now, uint8_t timeoutS) {
  if (!rtUntil || (int32_t)(rtUntil - now) <= 0) fill_solid(rt, MAX_LEDS, CRGB::Black);
  rtUntil = now + (timeoutS == 255 ? 3600000UL : (timeoutS ? timeoutS : 1) * 1000UL);
  activity();
  dirty = true;
}

static void pollUdp(uint32_t now) {
  int n;
  while ((n = udpWled.parsePacket()) > 0) {
    n = udpWled.read(udpBuf, sizeof udpBuf);
    if (n < 2) continue;
    uint8_t proto = udpBuf[0];
    int maxN = numLeds();
    if (proto == 1) {          // WARLS: index r g b …
      rtStart(now, udpBuf[1]);
      for (int i = 2; i + 3 < n; i += 4) if (udpBuf[i] < maxN) rt[udpBuf[i]] = CRGB(udpBuf[i + 1], udpBuf[i + 2], udpBuf[i + 3]);
    } else if (proto == 2) {   // DRGB: r g b … from LED 0
      rtStart(now, udpBuf[1]);
      for (int i = 2, k = 0; i + 2 < n && k < maxN; i += 3, k++) rt[k] = CRGB(udpBuf[i], udpBuf[i + 1], udpBuf[i + 2]);
    } else if (proto == 3) {   // DRGBW: white channel added to r g b
      rtStart(now, udpBuf[1]);
      for (int i = 2, k = 0; i + 3 < n && k < maxN; i += 4, k++)
        rt[k] = CRGB(qadd8(udpBuf[i], udpBuf[i + 3]), qadd8(udpBuf[i + 1], udpBuf[i + 3]), qadd8(udpBuf[i + 2], udpBuf[i + 3]));
    } else if (proto == 4 && n >= 4) {  // DNRGB: start (2 bytes, big end first), r g b …
      rtStart(now, udpBuf[1]);
      int k = (udpBuf[2] << 8) | udpBuf[3];
      for (int i = 4; i + 2 < n && k < maxN; i += 3, k++) rt[k] = CRGB(udpBuf[i], udpBuf[i + 1], udpBuf[i + 2]);
    }
  }
  while ((n = udpArt.parsePacket()) > 0) {
    n = udpArt.read(udpBuf, sizeof udpBuf);
    if (n < 18 || memcmp(udpBuf, "Art-Net\0", 8) != 0 || udpBuf[8] != 0x00 || udpBuf[9] != 0x50) continue;  // ArtDmx only
    int universe = udpBuf[14] | (udpBuf[15] << 8);
    int length = (udpBuf[16] << 8) | udpBuf[17];
    if (universe < artStart || 18 + length > n) continue;
    rtStart(now, 3);
    int k = (universe - artStart) * 170, maxN = numLeds();
    for (int i = 0; i + 2 < length && k < maxN; i += 3, k++) rt[k] = CRGB(udpBuf[18 + i], udpBuf[19 + i], udpBuf[20 + i]);
  }
  // E1.31 / sACN: universe 1 (+ the Art-Net start setting) = first 170 LEDs, RGB from slot 1.
  while ((n = udpE131.parsePacket()) > 0) {
    n = udpE131.read(udpBuf, sizeof udpBuf);
    if (n < 126 || memcmp(udpBuf + 4, "ASC-E1.17", 9) != 0 || udpBuf[21] != 0x04 || udpBuf[43] != 0x02 || udpBuf[125] != 0) continue;
    int universe = (udpBuf[113] << 8) | udpBuf[114];
    int slots = ((udpBuf[123] << 8) | udpBuf[124]) - 1;
    if (universe < 1 + artStart || 126 + slots > n) continue;
    rtStart(now, 3);
    int k = (universe - 1 - artStart) * 170, maxN = numLeds();
    for (int i = 0; i + 2 < slots && k < maxN; i += 3, k++) rt[k] = CRGB(udpBuf[126 + i], udpBuf[127 + i], udpBuf[128 + i]);
  }
  // DDP: byte offset in the strip (3 bytes per LED), data after a 10-byte header (14 with a time code).
  while ((n = udpDdp.parsePacket()) > 0) {
    n = udpDdp.read(udpBuf, sizeof udpBuf);
    if (n < 10 || (udpBuf[0] & 0xC0) != 0x40) continue;
    int head = (udpBuf[0] & 0x10) ? 14 : 10;
    uint32_t offset = ((uint32_t)udpBuf[4] << 24) | ((uint32_t)udpBuf[5] << 16) | (udpBuf[6] << 8) | udpBuf[7];
    int length = (udpBuf[8] << 8) | udpBuf[9];
    if (head + length > n || offset % 3) continue;
    rtStart(now, 3);
    int k = offset / 3, maxN = numLeds();
    for (int i = 0; i + 2 < length && k < maxN; i += 3, k++) rt[k] = CRGB(udpBuf[head + i], udpBuf[head + i + 1], udpBuf[head + i + 2]);
  }
  // Discovery: the apps broadcast "LEDPIANO?" and list every board that answers.
  while ((n = udpFind.parsePacket()) > 0) {
    n = udpFind.read(udpBuf, sizeof udpBuf);
    if (n >= 9 && memcmp(udpBuf, "LEDPIANO?", 9) == 0) {
      udpFind.beginPacket(udpFind.remoteIP(), udpFind.remotePort());
      udpFind.print("LEDPIANO " DEVICE_NAME);
      udpFind.endPacket();
    }
  }
}

// The app over Wi-Fi (TCP 7777): one app at a time; a new one replaces the old.
static void pollTcp() {
  if (tcpServer.hasClient()) {
    WiFiClient c = tcpServer.available();
    if (tcpClient && tcpClient.connected()) tcpClient.stop();
    tcpClient = c;
    tcpClient.setNoDelay(true);
    tcpApp = false;
    lastStatus = 0;  // send the status at once
    Serial.println("App connected over Wi-Fi");
  }
  if (tcpClient && !tcpClient.connected()) {
    tcpClient.stop();
    if (tcpApp) {
      tcpApp = false;
      appKind = APP_NONE;
      expected.clear();
      dirty = true;
    }
  }
  int budget = 1024;  // keep the LEDs and Bluetooth responsive
  while (tcpClient && tcpClient.available() && budget-- > 0) tcpInput.feed((uint8_t)tcpClient.read(), true);
}

// ---------------------------------------------------------------- Wi-Fi: web page + OTA
static String esc(const String& s) {
  String o;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '<') o += "&lt;"; else if (c == '>') o += "&gt;"; else if (c == '&') o += "&amp;";
    else if (c == '"') o += "&quot;"; else o += c;
  }
  return o;
}

static bool allowed() {
  if (webPassword.length() == 0) return true;
  if (web.authenticate("admin", webPassword.c_str())) return true;
  web.requestAuthentication();
  return false;
}

static String ipString() { return staMode ? WiFi.localIP().toString() : WiFi.softAPIP().toString(); }

static void pageRoot() {
  if (!allowed()) return;
  String h = F("<!doctype html><html lang=vi><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
               "<title>LED Piano Strip</title><style>body{font-family:sans-serif;max-width:560px;margin:auto;padding:16px;"
               "background:#111;color:#eee}fieldset{border:1px solid #444;border-radius:8px;margin:12px 0}input,select,button"
               "{font-size:16px;margin:4px 0;padding:6px;width:100%;box-sizing:border-box}button{background:#0a66d6;color:#fff;"
               "border:0;border-radius:6px}.row{display:flex;gap:6px;align-items:center}.row span{flex:1}"
               ".row button{width:auto}</style><h2>LED Piano Strip</h2>");
  h += "<p>Mạch: <b>" BOARD_LABEL "</b> · tối đa " + String(book.capacity) + " nốt mỗi bài, " + String(LIBRARY_MAX) + " bài</p>";
  h += "<p>Đàn Bluetooth: <b>" + String(pianoConnected ? "đã kết nối" : "chưa kết nối") + "</b><br>Ứng dụng: <b>" +
       String(appConnected ? "đã kết nối (Bluetooth)" : tcpApp ? "đã kết nối (Wi-Fi)" : serialApp || uartApp ? "đã kết nối (USB)" : "chưa kết nối") + "</b><br>Bài đang chọn: <b>" + (book.count ? esc(book.title[0] ? String(book.title) : String("(không tên)")) + " – " + String(book.count) + " nốt" : String("chưa có")) +
       "</b><br>Wi-Fi: <b>" + (staMode ? esc(WiFi.SSID()) : String("điểm phát riêng")) + "</b> · " + ipString() +
       "<br>Đèn: " + String(numLeds()) + " · " + String((int)cfg.ledsPerMetre) + " đèn/m · " + String(cfg.numKeys) + " phím</p>";
  h += F("<form method=post action=/cmd><button name=c value=test>Thử đèn</button><button name=c value=lesson>"
         "Bắt đầu tập bài đang chọn</button><button name=c value=stop>Dừng / tắt đèn</button></form>");
  // Song library: choose (▶) or delete (✕). On the board: BOOT double-press = next song.
  h += "<fieldset><legend>Thư viện bài (" + String(libCount) + "/" + String(LIBRARY_MAX) + ")</legend>";
  if (!libCount) h += F("Chưa có bài. Trong ứng dụng: Tùy chọn → “Gửi bài này vào ESP32”.");
  for (int i = libCount - 1; i >= 0; i--) {
    char title[TITLE_MAX + 1] = "";
    int count = 0;
    libInfo(libIds[i], title, count);
    h += "<form method=post action=/cmd class=row><input type=hidden name=id value=" + String(libIds[i]) + "><span>" +
         String(i + 1) + ". " + (libIds[i] == libCurrent ? "<b>" : "") + esc(title[0] ? String(title) : String("(không tên)")) +
         (libIds[i] == libCurrent ? "</b>" : "") + " · " + String(count) + " nốt</span>"
         "<button name=c value=song>▶</button><button name=c value=delsong style='background:#733'>✕</button></form>";
  }
  h += F("<small>Trên mạch: nhấn BOOT 1 lần = bắt đầu / hợp âm kế tiếp, nhấn 2 lần nhanh = bài kế tiếp, giữ 1 giây = dừng.</small></fieldset>");
  h += "<form method=post action=/save><fieldset><legend>Đèn</legend>Độ sáng (0–255)<input name=bright type=number min=0 max=255 value=" +
       String(brightness) + ">Giới hạn dòng nguồn 5 V (mA)<input name=ma type=number min=300 max=20000 value=" + String(milliamps) +
       ">Tự tắt sau (phút, 0 = không)<input name=autooff type=number min=0 max=240 value=" + String(autoOffMin) +
       ">Khi chơi tự do (không có ứng dụng)<select name=effect>";
  const char* fx[] = {"Tắt", "Màu tay phải", "Cầu vồng theo lực bấm"};
  for (int i = 0; i < 3; i++) h += "<option value=" + String(i) + (effect == i ? " selected>" : ">") + fx[i] + "</option>";
  h += "</select>Art-Net: universe đầu tiên<input name=artuni type=number min=0 max=32767 value=" + String(artStart) + "></fieldset>";
  h += "<fieldset><legend>Wi-Fi nhà bạn</legend>Tên Wi-Fi<input name=ssid value=\"" + esc(prefs.getString("ssid", "")) +
       "\">Mật khẩu (để trống = giữ nguyên)<input name=pass type=password>Mật khẩu trang này (tên: admin, để trống = không)"
       "<input name=webpw type=password placeholder='giữ nguyên'></fieldset><button>Lưu</button></form>";
  h += F("<fieldset><legend>Cập nhật firmware (.bin)</legend><form method=post action=/update enctype=multipart/form-data>"
         "<input type=file name=f accept=.bin><button>Tải lên</button></form></fieldset>"
         "<p>Nhận tín hiệu WLED (UDP 21324) và Art-Net (UDP 6454).</p></html>");
  web.send(200, "text/html; charset=utf-8", h);
}

static void pageSave() {
  if (!allowed()) return;
  if (web.hasArg("bright")) { brightness = constrain(web.arg("bright").toInt(), 0, 255); prefs.putUChar("bright", brightness); }
  if (web.hasArg("ma")) {
    milliamps = constrain(web.arg("ma").toInt(), 300, 20000);
    prefs.putInt("ma", milliamps);
    FastLED.setMaxPowerInVoltsAndMilliamps(5, milliamps);
  }
  if (web.hasArg("autooff")) { autoOffMin = constrain(web.arg("autooff").toInt(), 0, 240); prefs.putInt("autooff", autoOffMin); }
  if (web.hasArg("effect")) { effect = constrain(web.arg("effect").toInt(), 0, 2); prefs.putInt("effect", effect); }
  if (web.hasArg("artuni")) { artStart = constrain(web.arg("artuni").toInt(), 0, 32767); prefs.putInt("artuni", artStart); }
  if (web.arg("webpw").length()) { webPassword = web.arg("webpw"); prefs.putString("webpw", webPassword); }
  bool restart = false;
  if (web.hasArg("ssid") && web.arg("ssid") != prefs.getString("ssid", "")) { prefs.putString("ssid", web.arg("ssid")); restart = true; }
  if (web.arg("pass").length()) { prefs.putString("pass", web.arg("pass")); restart = true; }
  dirty = true;
  web.send(200, "text/html; charset=utf-8", restart
      ? "<meta charset=utf-8>Đã lưu. ESP32 khởi động lại và vào Wi-Fi mới – mở lại http://ledpiano.local sau 20 giây."
      : "<meta charset=utf-8><meta http-equiv=refresh content='1;url=/'>Đã lưu.");
  if (restart) { delay(500); ESP.restart(); }
}

static void pageCmd() {
  if (!allowed()) return;
  String c = web.arg("c");
  if (c == "test") sweep(handColour(false, false));
  else if (c == "lesson") startLesson();
  else if (c == "song") { lesson.stop(); if (libLoad(web.arg("id").toInt())) startLesson(); lastStatus = 0; }
  else if (c == "delsong") { lesson.stop(); libDelete(web.arg("id").toInt()); lastStatus = 0; }
  else if (c == "stop") { lesson.stop(); appKind = APP_NONE; rtUntil = 0; memset(synthKeys, 0, sizeof synthKeys); }
  activity();
  dirty = true;
  web.sendHeader("Location", "/");
  web.send(303);
}

static void startWifi() {
  String ssid = prefs.getString("ssid", ""), pass = prefs.getString("pass", "");
  WiFi.setHostname("ledpiano");
  if (ssid.length()) {
    WiFi.mode(WIFI_STA);
#ifdef WIFI_TX_POWER
    WiFi.setTxPower(WIFI_TX_POWER);  // ESP32-C3 Super Mini: see the board block at the top
#endif
    WiFi.setAutoReconnect(true);
    WiFi.begin(ssid.c_str(), pass.c_str());
    Serial.printf("Wi-Fi: connecting to %s", ssid.c_str());
    for (int i = 0; i < 30 && WiFi.status() != WL_CONNECTED; i++) { delay(500); Serial.print('.'); }
    Serial.println();
    staMode = WiFi.status() == WL_CONNECTED;
  }
  if (!staMode) {
    // No home Wi-Fi (or it failed): the ESP32 makes its own network so you can set it up.
    char name[24];
    uint64_t mac = ESP.getEfuseMac();
    snprintf(name, sizeof name, "LED-Piano-%04X", (unsigned)((mac >> 32) & 0xFFFF));
    WiFi.mode(WIFI_AP);
    WiFi.softAP(name, AP_PASSWORD);
#ifdef WIFI_TX_POWER
    WiFi.setTxPower(WIFI_TX_POWER);
#endif
    Serial.printf("Wi-Fi: own network \"%s\", password \"%s\"\n", name, AP_PASSWORD);
  }
  Serial.println("Web page: http://" + ipString() + "  (or http://ledpiano.local)");
  MDNS.begin("ledpiano");
  MDNS.setInstanceName(DEVICE_NAME);
  MDNS.addService("http", "tcp", 80);
  // Bonjour / mDNS: the apps find the board by themselves (iPhone/iPad can only find it this way).
  MDNS.addService("ledpiano", "tcp", 7777);
  MDNS.addServiceTxt("ledpiano", "tcp", "board", BOARD_LABEL);

  web.on("/", HTTP_GET, pageRoot);
  web.on("/save", HTTP_POST, pageSave);
  web.on("/cmd", HTTP_POST, pageCmd);
  web.on("/status.json", HTTP_GET, []() {
    web.send(200, "application/json",
             String("{\"piano\":") + (pianoConnected ? "true" : "false") + ",\"app\":" + (appConnected || serialApp || tcpApp || uartApp ? "true" : "false") +
                 ",\"song_notes\":" + book.count + ",\"library\":" + libCount + ",\"board\":\"" BOARD_LABEL "\"" + ",\"leds\":" + numLeds() + ",\"lesson\":" + (lesson.running ? "true" : "false") + "}");
  });
  web.on("/update", HTTP_POST,
         []() {
           if (!allowed()) return;
           bool ok = !Update.hasError();
           web.sendHeader("Connection", "close");
           web.send(200, "text/html; charset=utf-8",
                    ok ? "<meta charset=utf-8>Cập nhật xong, đang khởi động lại…" : "<meta charset=utf-8>Lỗi cập nhật – thử lại.");
           delay(500);
           if (ok) ESP.restart();
         },
         []() {
           if (webPassword.length() && !web.authenticate("admin", webPassword.c_str())) return;
           HTTPUpload& up = web.upload();
           if (up.status == UPLOAD_FILE_START) {
             fill_solid(leds, MAX_LEDS, CRGB::Black);
             FastLED.show();
             Update.begin(UPDATE_SIZE_UNKNOWN);
           } else if (up.status == UPLOAD_FILE_WRITE) {
             Update.write(up.buf, up.currentSize);
           } else if (up.status == UPLOAD_FILE_END) {
             Update.end(true);
           }
         });
  web.begin();
  udpWled.begin(21324);
  udpArt.begin(6454);
  udpE131.begin(5568);
  udpDdp.begin(4048);
  udpFind.begin(7778);
  tcpServer.begin();
  tcpServer.setNoDelay(true);
  wifiStarted = true;
}

// ---------------------------------------------------------------- status to the app
static void sendStatus(uint32_t now) {
  if ((int32_t)(now - lastStatus) < 3000 && lastStatus) return;
  lastStatus = now;
  IPAddress ip = staMode ? WiFi.localIP() : WiFi.softAPIP();
  uint8_t p[9] = {(uint8_t)((pianoConnected ? 1 : 0) | (staMode && WiFi.status() == WL_CONNECTED ? 2 : 0) | (book.count ? 4 : 0) |
                             (IS_S3 ? 8 : 0) | (IS_C3 ? 16 : 0)),  // bit3: ESP32-S3 board, bit4: ESP32-C3
                  ip[0], ip[1], ip[2], ip[3], (uint8_t)(book.count & 0xFF), (uint8_t)(book.count >> 8),
                  (uint8_t)(book.capacity & 0xFF), (uint8_t)(book.capacity >> 8)};  // notes per song this board holds
  sendToApp(OP_STATUS, p, sizeof p);
}

// ---------------------------------------------------------------- the board's own RGB LED (ESP32-S3)
static void showBoardLed(uint32_t now) {
#if BOARD_RGB_PIN >= 0
  static uint32_t last = 0;
  static uint32_t shown = 0xFFFFFFFF;
  if (now - last < 300) return;
  last = now;
  bool app = appConnected || serialApp || tcpApp || uartApp;
  uint32_t c = asleep ? 0 : pianoConnected && app ? 0x200020 : app ? 0x000028 : pianoConnected ? 0x200020 : 0x001000;
  if (c == shown) return;
  shown = c;
  neopixelWrite(BOARD_RGB_PIN, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);  // dim: green ready, blue app, purple piano
#elif defined(BOARD_LED_PIN)
  // One blue LED (ESP32-C3 Super Mini): short blink every 2 s = ready, on = app or piano connected,
  // on with a short blink every second = app AND piano, off = asleep.
  bool app = appConnected || serialApp || tcpApp || uartApp;
  bool on;
  if (asleep) on = false;
  else if (app && pianoConnected) on = (now % 1000) >= 100;
  else if (app || pianoConnected) on = true;
  else on = (now % 2000) < 80;
  static int shown = -1;
  if ((int)on == shown) return;
  shown = on;
  digitalWrite(BOARD_LED_PIN, on ? LOW : HIGH);  // lit when LOW
#else
  (void)now;
#endif
}

// ---------------------------------------------------------------- BOOT button
// Short press: start the stored song / skip to the next chord. Double press: next song in the library.
// Long press (1 s): stop.
static void pollButton(uint32_t now) {
  static bool down = false, longDone = false, waitingSecond = false;
  static uint32_t since = 0, releasedAt = 0;
  bool pressed = digitalRead(BUTTON_PIN) == LOW;
  if (waitingSecond && !pressed && now - releasedAt > 350) {  // no second press: it was a single press
    waitingSecond = false;
    activity();
    if (!lesson.running) startLesson();
    else {
      lesson.next(book);
      if (!lesson.running) lessonDone();
    }
    dirty = true;
  }
  if (pressed && !down) { down = true; longDone = false; since = now; }
  if (pressed && down && !longDone && now - since > 1000) {  // long press: stop
    longDone = true;
    lesson.stop();
    memset(heldVel, 0, sizeof heldVel);
    dirty = true;
    activity();
  }
  if (!pressed && down) {
    down = false;
    if (!longDone && now - since > 30) {
      if (waitingSecond) {  // second press within 350 ms: next song
        waitingSecond = false;
        activity();
        libNext();
        dirty = true;
      } else {
        waitingSecond = true;
        releasedAt = now;
      }
    }
  }
}

// ---------------------------------------------------------------- setup / loop
void setup() {
  Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT
  Serial0.begin(115200);  // ESP32-S3: the "COM" USB-C port (CP2102N / CH343) talks to the apps too;
                          // ESP32-C3 Super Mini: pins 20 (RX) / 21 (TX), e.g. for a USB-serial adapter
#endif
  pinMode(BUTTON_PIN, INPUT_PULLUP);
#ifdef BOARD_LED_PIN
  pinMode(BOARD_LED_PIN, OUTPUT);
  digitalWrite(BOARD_LED_PIN, HIGH);  // off
#endif
  for (auto& f : flashes) f.led = -1;
  rxQueue = xQueueCreate(24, sizeof(Chunk));
  serialInput.appFlag = &serialApp;
  uartInput.appFlag = &uartApp;
  tcpInput.appFlag = &tcpApp;

  loadSettings();
  // Song storage: PSRAM on an ESP32-S3 N16R8 (20000-note songs), else the built-in 3000 notes.
#if IS_S3 && defined(BOARD_HAS_PSRAM)
  if (psramFound()) {
    SongNote* a = (SongNote*)ps_malloc(sizeof(SongNote) * SONG_NOTES_MAX);
    SongNote* b = (SongNote*)ps_malloc(sizeof(SongNote) * SONG_NOTES_MAX);
    if (a && b) book.setStorage(a, b, SONG_NOTES_MAX);
  }
#endif
  book.ensure();
  Serial.printf("%s: up to %d notes per song, %d songs\n", BOARD_LABEL, book.capacity, LIBRARY_MAX);
  if (LittleFS.begin(true)) libOpenAtBoot();

  FastLED.addLeds<WS2812B, LED_PIN, GRB>(leds, MAX_LEDS);  // extra LEDs past the strip's end just stay black
  FastLED.setBrightness(brightness);
  FastLED.setMaxPowerInVoltsAndMilliamps(5, milliamps);
  sweep(handColour(false, false));

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(185);

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* nus = server->createService(NUS_SERVICE);
  BLECharacteristic* rx = nus->createCharacteristic(NUS_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks(SRC_NUS));
  BLECharacteristic* tx = nus->createCharacteristic(NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  tx->addDescriptor(new BLE2902());
  nus->start();

  BLEService* ws = server->createService(WS_SERVICE);
  BLECharacteristic* wsRx = ws->createCharacteristic(WS_WRITE, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  wsRx->setCallbacks(new RxCallbacks(SRC_WS));
  wsNotify = ws->createCharacteristic(WS_NOTIFY, BLECharacteristic::PROPERTY_NOTIFY);
  wsNotify->addDescriptor(new BLE2902());
  ws->start();

  // Bluetooth MIDI device for Synthesia's key lights.
  BLEService* midi = server->createService(MIDI_SERVICE);
  midiChar = midi->createCharacteristic(MIDI_CHAR, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE_NR |
                                                       BLECharacteristic::PROPERTY_NOTIFY);
  midiChar->addDescriptor(new BLE2902());
  midiChar->setCallbacks(new RxCallbacks(SRC_SYNTH));
  midi->start();

  // Name in the advertisement (the apps find the strip by its name), MIDI service in the scan response.
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  BLEAdvertisementData advData, scanData;
  advData.setFlags(0x06);
  advData.setName(DEVICE_NAME);
  scanData.setCompleteServices(BLEUUID(MIDI_SERVICE));
  adv->setAdvertisementData(advData);
  adv->setScanResponseData(scanData);
  BLEDevice::startAdvertising();

  startWifi();
  lastActivity = millis();
  Serial.println("LED Piano Strip 2 ready: Bluetooth \"" DEVICE_NAME "\", USB serial 115200, Wi-Fi web page.");
}

void loop() {
  uint32_t now = millis();

  Chunk c;
  while (xQueueReceive(rxQueue, &c, 0) == pdTRUE) handleChunk(c);
  while (Serial.available()) serialInput.feed((uint8_t)Serial.read(), true);
#if ARDUINO_USB_CDC_ON_BOOT
  while (Serial0.available()) uartInput.feed((uint8_t)Serial0.read(), true);
#endif
  showBoardLed(now);

  if (wifiStarted) {
    web.handleClient();
    pollUdp(now);
    pollTcp();
  }
  pollPiano(now);
  pollButton(now);
  sendStatus(now);

  if (!asleep && autoOffMin > 0 && now - lastActivity > (uint32_t)autoOffMin * 60000UL) {
    asleep = true;  // LEDs off until the next message, note or button press
    lesson.stop();
    dirty = true;
  }
  if (rtUntil && (int32_t)(rtUntil - now) <= 0) { rtUntil = 0; dirty = true; }

  static uint32_t lastDraw = 0;
  static bool animating = false;
  if ((dirty || animating) && now - lastDraw >= 8) {  // at most ~120 pictures a second
    dirty = false;
    lastDraw = now;
    animating = render(now);
  }
  delay(1);
}
