// Piano.h - pure C++ learning logic for the LED Piano Strip firmware 2 (no Arduino calls, unit-tested
// on a PC with test/test_keymap.cpp). The .ino wires it to Bluetooth, Wi-Fi and the LEDs.
//
//  * BleMidiParser   - decodes Bluetooth LE MIDI packets from a digital piano (or Synthesia's key lights)
//  * Live settings   - OP_CONFIG from the app: strip geometry and hand colours, so the ESP32 lights keys itself
//  * KeySet          - the keys the learner should press now (OP_EXPECT) → instant green / red flash
//  * SongBook        - a song stored on the ESP32 (OP_SONG_BEGIN / DATA / END), 4000 notes max
//  * Lesson          - practise that song without the phone: light the next chord, wait for the right keys
#pragma once
#include <stdint.h>
#include <string.h>
#include "KeyMap.h"

namespace ledpiano {

static const uint8_t OP_CONFIG = 0x04, OP_EXPECT = 0x05, OP_SONG_BEGIN = 0x06, OP_SONG_DATA = 0x07,
                     OP_SONG_END = 0x08, OP_SONG_TITLE = 0x09, OP_NOTE = 0x10, OP_STATUS = 0x11;
static const int TITLE_MAX = 60;  // bytes of UTF-8

// Builds an "A5 op len payload checksum" message (ESP32 → app notifications). Returns its length.
inline int buildMessage(uint8_t op, const uint8_t* payload, uint8_t len, uint8_t* out) {
  out[0] = 0xA5; out[1] = op; out[2] = len;
  uint8_t chk = op ^ len;
  for (int i = 0; i < len; i++) { out[3 + i] = payload[i]; chk ^= payload[i]; }
  out[3 + len] = chk;
  return len + 4;
}

// ---------------------------------------------------------------- Bluetooth LE MIDI
// Packet: header (bit7 = 1, timestamp high), then for each message: [timestamp low (bit7 = 1)] status
// data…; the status byte may be left out (running status). System messages are skipped.
class BleMidiParser {
 public:
  // onMessage(status, data1, data2) is called for every channel message in the packet.
  template <typename F>
  void parse(const uint8_t* p, int n, F onMessage) {
    if (n < 2 || !(p[0] & 0x80)) return;
    int i = 1;
    uint8_t d[2]; int nd = 0;
    while (i < n) {
      uint8_t b = p[i];
      if (b & 0x80) {
        // timestamp byte, followed by a status byte or (running status) data
        if (i + 1 < n && (p[i + 1] & 0x80)) {
          i++;
          uint8_t st = p[i];
          if (st >= 0xF0) { running_ = 0; i = skipSystem(p, n, i); continue; }
          running_ = st; nd = 0; i++;
          continue;
        }
        i++;  // timestamp before running-status data
        continue;
      }
      if (!running_) { i++; continue; }
      d[nd++] = b; i++;
      if (nd == dataLen(running_)) {
        onMessage(running_, d[0], nd > 1 ? d[1] : 0);
        nd = 0;
      }
    }
  }

 private:
  static int dataLen(uint8_t st) {
    uint8_t t = st & 0xF0;
    return (t == 0xC0 || t == 0xD0) ? 1 : 2;
  }
  static int skipSystem(const uint8_t* p, int n, int i) {
    if (p[i] == 0xF0) {  // SysEx: skip to F7
      while (i < n && p[i] != 0xF7) i++;
    }
    return i + 1;
  }
  uint8_t running_ = 0;
};

// ---------------------------------------------------------------- settings from the app
struct Colours {
  uint8_t right[3] = {0x0A, 0x66, 0xD6};
  uint8_t left[3] = {0xF8, 0xB8, 0x4E};
};

// OP_CONFIG payload: numLeds(2) density lowest keys firstLed(2, signed) reversed R(3) L(3).
inline bool parseConfig(const uint8_t* d, int len, int maxLeds, StripConfig& cfg, Colours& col) {
  if (len < 14) return false;
  int n = d[0] | (d[1] << 8);
  if (n < 1) return false;
  cfg.numLeds = n > maxLeds ? maxLeds : n;
  cfg.ledsPerMetre = d[2] ? d[2] : 144;
  cfg.lowestPitch = d[3];
  cfg.numKeys = d[4] > MAX_KEYS ? MAX_KEYS : d[4];
  cfg.firstLed = (int16_t)(d[5] | (d[6] << 8));
  cfg.reversed = d[7] != 0;
  memcpy(col.right, d + 8, 3);
  memcpy(col.left, d + 11, 3);
  return true;
}

// ---------------------------------------------------------------- expected keys
struct KeySet {
  uint32_t bits[4] = {0, 0, 0, 0};
  void clear() { bits[0] = bits[1] = bits[2] = bits[3] = 0; }
  void add(uint8_t p) { if (p < 128) bits[p >> 5] |= 1u << (p & 31); }
  void remove(uint8_t p) { if (p < 128) bits[p >> 5] &= ~(1u << (p & 31)); }
  bool has(uint8_t p) const { return p < 128 && (bits[p >> 5] >> (p & 31)) & 1u; }
  bool empty() const { return !(bits[0] | bits[1] | bits[2] | bits[3]); }
  void set(const uint8_t* d, int n) { clear(); for (int i = 0; i < n; i++) add(d[i]); }
};

// ---------------------------------------------------------------- stored song
struct SongNote {  // 5 bytes, no padding: 3000 notes ≈ 15 KB
  uint8_t t[3];   // start, milliseconds from the beginning (24 bit)
  uint8_t pitch;
  uint8_t hand;   // 0 right, 1 left
  uint32_t ms() const { return t[0] | (t[1] << 8) | ((uint32_t)t[2] << 16); }
};

class SongBook {
 public:
  static const int MAX_NOTES = 3000;  // default size (ESP32-WROOM-32); an ESP32-S3 with PSRAM gives more
  SongNote* notes = nullptr;
  int count = 0;      // committed notes (0 = no song)
  int capacity = 0;

  // Storage from elsewhere (e.g. PSRAM on an ESP32-S3: 2 × 20000 notes = 200 KB). Both arrays hold `cap` notes.
  void setStorage(SongNote* n, SongNote* staging, int cap) {
    notes = n;
    staging_ = staging;
    capacity = cap;
    count = 0;
  }
  // Built-in storage of MAX_NOTES when nothing else was given.
  void ensure() {
    if (notes) return;
    setStorage(own_, ownStaging_, MAX_NOTES);
  }

  char title[TITLE_MAX + 1] = "";  // name of the committed song (from OP_SONG_TITLE, may be empty)

  // Feeds one song message; returns true when a complete song has just been committed.
  bool feed(uint8_t op, const uint8_t* d, int len) {
    ensure();
    if (op == OP_SONG_TITLE) {  // optional, sent just before BEGIN (firmware 2.1)
      int n = len < TITLE_MAX ? len : TITLE_MAX;
      memcpy(pendingTitle_, d, n);
      pendingTitle_[n] = 0;
      return false;
    }
    if (op == OP_SONG_BEGIN && len >= 2) {
      requested_ = d[0] | (d[1] << 8);
      expected_ = requested_ > capacity ? capacity : requested_;  // too long for this board: keep the beginning
      received_ = 0;
      loading_ = true;
      return false;
    }
    if (op == OP_SONG_DATA && loading_ && len >= 2) {
      int i = d[0] | (d[1] << 8);
      if (i >= expected_ && received_ == expected_) return false;  // beyond what fits: ignore
      if (i != received_) { loading_ = false; return false; }      // a message got lost: give up, keep the old song
      for (int k = 2; k + 4 < len && i < expected_; k += 5, i++) {
        staging_[i].t[0] = d[k]; staging_[i].t[1] = d[k + 1]; staging_[i].t[2] = d[k + 2];
        staging_[i].pitch = d[k + 3];
        staging_[i].hand = d[k + 4];
      }
      received_ = i;
      return false;
    }
    if (op == OP_SONG_END && loading_ && len >= 2) {
      loading_ = false;
      int n = d[0] | (d[1] << 8);
      if (n != requested_ || received_ != expected_) return false;  // something got lost: keep the old song
      n = expected_;
      memcpy(notes, staging_, sizeof(SongNote) * n);
      count = n;
      memcpy(title, pendingTitle_, sizeof title);
      pendingTitle_[0] = 0;
      return true;
    }
    return false;
  }

 private:
  SongNote own_[MAX_NOTES];
  SongNote ownStaging_[MAX_NOTES];
  SongNote* staging_ = nullptr;
  char pendingTitle_[TITLE_MAX + 1] = "";
  int expected_ = 0, received_ = 0, requested_ = 0;
  bool loading_ = false;
};

// ---------------------------------------------------------------- song library files
// One song per file: "LPS1", title length, title (UTF-8), note count (2 bytes), notes (5 bytes each).
namespace songfile {
static const int HEADER_MAX = 4 + 1 + TITLE_MAX + 2;

// Writes the header into `out` (at least HEADER_MAX bytes); returns its length.
inline int header(const SongBook& b, uint8_t* out) {
  int t = (int)strnlen(b.title, TITLE_MAX);
  out[0] = 'L'; out[1] = 'P'; out[2] = 'S'; out[3] = '1';
  out[4] = (uint8_t)t;
  memcpy(out + 5, b.title, t);
  out[5 + t] = b.count & 0xFF;
  out[6 + t] = (b.count >> 8) & 0xFF;
  return 7 + t;
}

// Reads a header from the first bytes of a file: title (zero-terminated) and note count.
// Returns the header length, or 0 when it is not a song file.
inline int parse(const uint8_t* in, int n, char* title, int& count) {
  if (n < 7 || in[0] != 'L' || in[1] != 'P' || in[2] != 'S' || in[3] != '1') return 0;
  int t = in[4];
  if (t > TITLE_MAX || 7 + t > n) return 0;
  memcpy(title, in + 5, t);
  title[t] = 0;
  count = in[5 + t] | (in[6 + t] << 8);
  return 7 + t;
}
}  // namespace songfile

// ---------------------------------------------------------------- practise without the phone
// Wait mode on the ESP32: the next chord (notes starting within 40 ms) lights up; when every key of
// it has been pressed on the piano the lesson moves on.
class Lesson {
 public:
  static const uint32_t CHORD_MS = 40;
  bool running = false;
  int start = 0, end = 0;   // current chord = book.notes[start, end)
  KeySet waiting;           // keys of the current chord not pressed yet

  void begin(const SongBook& book) {
    running = book.count > 0;
    start = 0;
    load(book);
  }
  void stop() { running = false; waiting.clear(); }
  bool finished(const SongBook& book) const { return start >= book.count; }

  // A key was pressed; returns +1 = chord complete (moved on), 0 = right key, -1 = wrong key.
  int press(const SongBook& book, uint8_t pitch) {
    if (!running) return 0;
    bool inChord = false;
    for (int i = start; i < end; i++) if (book.notes[i].pitch == pitch) inChord = true;
    if (!inChord) return -1;
    waiting.remove(pitch);
    if (!waiting.empty()) return 0;
    next(book);
    return 1;
  }
  // Manual step (no piano connected): button press moves on.
  void next(const SongBook& book) {
    start = end;
    if (start >= book.count) { running = false; waiting.clear(); return; }
    load(book);
  }

 private:
  void load(const SongBook& book) {
    waiting.clear();
    end = start;
    if (start >= book.count) return;
    uint32_t t0 = book.notes[start].ms();
    while (end < book.count && book.notes[end].ms() - t0 <= CHORD_MS) waiting.add(book.notes[end++].pitch);
  }
};

}  // namespace ledpiano
