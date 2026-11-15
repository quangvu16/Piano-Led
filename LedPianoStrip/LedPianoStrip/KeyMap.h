// KeyMap.h - pure C++ logic for the LED Piano strip (no Arduino calls, so it can be unit-tested on a PC).
//
// 1) FrameParser: understands the Led Organ protocol that the LED Piano apps send
//    over Bluetooth (Nordic UART) or USB serial:
//        7F  key state  key state ...  6E
//    The frame is the complete picture: every key not listed is OFF.
//    "7F 01 00 6E" = all off. BLE writes are cut into 20-byte pieces, so the
//    parser keeps state between calls.
//    key   = 1..88, counted from the RIGHT end (1 = C8, 88 = A0) when the app's
//            "Organ key" setting is A0 (21).
//    state = 1 right hand white key, 2 right hand black key,
//            3 left hand white key,  4 left hand black key, 0 off.
//
// 2) KeyMap: works out which LED sits above each piano key from real key sizes
//    (white key 23.5 mm wide, black keys centred on the gap between whites) and
//    the strip's LED spacing. So it works for 60, 74, 96 or 144 LEDs/metre.
//
// 3) PixelParser: the "WS2812B" message the apps send when they drive the LEDs
//    themselves (the app does the key→LED maths and the colours, like
//    `leds[i] = CRGB(r, g, b)` in the FastLED example):
//        A5  op  len  payload[len]  checksum      (checksum = op ^ len ^ every payload byte)
//    op 0x01 PIXELS      payload = n × (indexLow, indexHigh, r, g, b): all LEDs off, then these on, show
//    op 0x02 BRIGHTNESS  payload = 1 byte (0..255)
//    op 0x03 FILL        payload = r, g, b: every LED that colour
#pragma once
#include <stdint.h>
#include <math.h>

namespace ledpiano {

static const int MAX_KEYS = 88;
static const int MAX_LIT = 32;  // the apps light at most 1 key per hand; 32 leaves room for chords

struct LitKey {
  uint8_t key;    // 1..88 as sent by the app
  uint8_t state;  // 1..4
};

class FrameParser {
 public:
  // Feed one received byte. Returns true when a complete frame has just ended;
  // then keys()/count() hold that frame.
  bool feed(uint8_t b) {
    if (b == 0x7F) {  // start: begin a new frame (also recovers from a lost end byte)
      inFrame_ = true;
      n_ = 0;
      half_ = false;
      return false;
    }
    if (!inFrame_) return false;
    if (b == 0x6E) {  // end
      inFrame_ = false;
      count_ = 0;
      for (int i = 0; i < n_; i++)
        if (buf_[i].state != 0 && buf_[i].key >= 1 && buf_[i].key <= MAX_KEYS) keys_[count_++] = buf_[i];
      return true;
    }
    if (!half_) {
      pendingKey_ = b;
      half_ = true;
    } else {
      half_ = false;
      if (n_ < MAX_LIT) buf_[n_++] = LitKey{pendingKey_, b};
    }
    return false;
  }
  int count() const { return count_; }
  const LitKey& key(int i) const { return keys_[i]; }

 private:
  bool inFrame_ = false, half_ = false;
  uint8_t pendingKey_ = 0;
  LitKey buf_[MAX_LIT];
  int n_ = 0;
  LitKey keys_[MAX_LIT];
  int count_ = 0;
};

// Is this MIDI note a black key?
inline bool isBlack(int pitch) {
  int pc = ((pitch % 12) + 12) % 12;
  return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
}

// Centre of a key in millimetres from the left edge of the lowest key on the keyboard.
inline float keyCentreMm(int pitch, int lowestPitch, float whiteMm) {
  // Count white keys from the lowest key up to (not including) this one.
  int whites = 0;
  for (int p = lowestPitch; p < pitch; p++)
    if (!isBlack(p)) whites++;
  if (!isBlack(pitch)) return (whites + 0.5f) * whiteMm;  // middle of the white key
  return whites * whiteMm;                                // black key sits on the gap
}

struct StripConfig {
  int numLeds = 144;          // LEDs on the strip
  float ledsPerMetre = 144;   // 60, 74, 96, 144 …
  int lowestPitch = 21;       // MIDI note of the leftmost key on YOUR piano (A0 = 21, 61 keys: C2 = 36)
  int numKeys = 88;           // keys on your piano (88, 76, 61, 49)
  float whiteKeyMm = 23.5f;   // most pianos and keyboards; measure 7 white keys and divide by 7
  int firstLed = 0;           // LED index above the left edge of the lowest key (fine-tune here)
  bool reversed = false;      // true if the strip's input end is on the RIGHT (treble) side
};

class KeyMap {
 public:
  void build(const StripConfig& c) {
    cfg_ = c;
    for (int k = 0; k < MAX_KEYS; k++) ledByPitchOffset_[k] = -1;
    float mmPerLed = 1000.0f / c.ledsPerMetre;
    for (int k = 0; k < c.numKeys && k < MAX_KEYS; k++) {
      int pitch = c.lowestPitch + k;
      // Nearest LED to the key's centre (LED i sits i × spacing from the left edge of the lowest key).
      int idx = c.firstLed + (int)floorf(keyCentreMm(pitch, c.lowestPitch, c.whiteKeyMm) / mmPerLed + 0.5f);
      if (c.reversed) idx = c.numLeds - 1 - idx;
      ledByPitchOffset_[k] = (idx >= 0 && idx < c.numLeds) ? idx : -1;
    }
  }

  // LED for a MIDI note, or -1 when that key has no LED.
  int ledForPitch(int pitch) const {
    int k = pitch - cfg_.lowestPitch;
    if (k < 0 || k >= cfg_.numKeys || k >= MAX_KEYS) return -1;
    return ledByPitchOffset_[k];
  }

  // The app's key byte (1 = C8 … 88 = A0, with "Organ key" = A0) back to a MIDI note.
  static int pitchForKeyByte(int keyByte) { return 109 - keyByte; }

  int ledForKeyByte(int keyByte) const { return ledForPitch(pitchForKeyByte(keyByte)); }

 private:
  StripConfig cfg_;
  int ledByPitchOffset_[MAX_KEYS] = {};
};

struct Pixel {
  uint16_t index;
  uint8_t r, g, b;
};

static const uint8_t OP_PIXELS = 0x01, OP_BRIGHTNESS = 0x02, OP_FILL = 0x03;
static const int MAX_PIXELS = 50;  // 250-byte payload / 5

class PixelParser {
 public:
  // Feed one byte; returns the op code when a whole, valid message has arrived, else 0.
  // A byte that is not part of a message is ignored, so garbage never lights anything.
  uint8_t feed(uint8_t b) {
    switch (st_) {
      case IDLE:
        if (b == 0xA5) st_ = OP;
        return 0;
      case OP:
        op_ = b; chk_ = b; st_ = LEN;
        return 0;
      case LEN:
        len_ = b; chk_ ^= b; got_ = 0;
        st_ = len_ ? DATA : CHECK;
        return 0;
      case DATA:
        data_[got_++] = b; chk_ ^= b;
        if (got_ == len_) st_ = CHECK;
        return 0;
      case CHECK: {
        st_ = IDLE;
        if (b != chk_) return 0;  // damaged message: drop it
        return accept() ? op_ : 0;
      }
    }
    return 0;
  }
  bool active() const { return st_ != IDLE; }
  int count() const { return n_; }
  const Pixel& pixel(int i) const { return px_[i]; }
  uint8_t brightness() const { return bright_; }
  uint8_t r() const { return fill_[0]; }
  uint8_t g() const { return fill_[1]; }
  uint8_t b() const { return fill_[2]; }
  // Raw payload of the last message (for the newer ops handled in Piano.h).
  const uint8_t* data() const { return data_; }
  uint8_t length() const { return len_; }

 private:
  bool accept() {
    if (op_ == OP_PIXELS) {
      if (len_ % 5) return false;
      n_ = 0;
      for (int i = 0; i + 4 < len_ && n_ < MAX_PIXELS; i += 5)
        px_[n_++] = Pixel{(uint16_t)(data_[i] | (data_[i + 1] << 8)), data_[i + 2], data_[i + 3], data_[i + 4]};
      return true;
    }
    if (op_ == OP_BRIGHTNESS && len_ == 1) { bright_ = data_[0]; return true; }
    if (op_ == OP_FILL && len_ == 3) { fill_[0] = data_[0]; fill_[1] = data_[1]; fill_[2] = data_[2]; return true; }
    return op_ >= 0x04 && op_ <= 0x1F;  // config, expected keys, songs … (see Piano.h)
  }
  enum { IDLE, OP, LEN, DATA, CHECK } st_ = IDLE;
  uint8_t op_ = 0, len_ = 0, chk_ = 0;
  int got_ = 0;
  uint8_t data_[255];
  Pixel px_[MAX_PIXELS];
  int n_ = 0;
  uint8_t bright_ = 0, fill_[3] = {0, 0, 0};
};

}  // namespace ledpiano
