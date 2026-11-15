// PC tests for Piano.h (firmware 2 learning logic). Build: g++ -std=c++17 test/test_piano.cpp -o t && ./t
#include "../LedPianoStrip/Piano.h"
#include "vectors.h"
#include <cstdio>
#include <vector>
using namespace ledpiano;
static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)

struct Msg { int st, d1, d2; };

int main() {
  // --- Bluetooth LE MIDI packets
  BleMidiParser mp;
  std::vector<Msg> got;
  auto cb = [&](uint8_t st, uint8_t a, uint8_t b) { got.push_back({st, a, b}); };
  const uint8_t p1[] = {0x80, 0x80, 0x90, 60, 100};                       // one note on
  mp.parse(p1, sizeof p1, cb);
  const uint8_t p2[] = {0x80, 0x81, 0x90, 62, 90, 0x82, 64, 80, 0x83, 0x80, 62, 0};  // running status + note off
  mp.parse(p2, sizeof p2, cb);
  const uint8_t p3[] = {0x80, 0x80, 0xF0, 1, 2, 3, 0xF7, 0x80, 0x99, 36, 50};  // SysEx skipped, then ch10 note
  mp.parse(p3, sizeof p3, cb);
  const uint8_t p4[] = {0x80, 0x80, 0xC0, 5, 0x80, 0x90, 70, 1};          // program change (1 data byte)
  mp.parse(p4, sizeof p4, cb);
  CHECK(got.size() == 7);
  CHECK(got[0].st == 0x90 && got[0].d1 == 60 && got[0].d2 == 100);
  CHECK(got[1].d1 == 62 && got[2].st == 0x90 && got[2].d1 == 64 && got[2].d2 == 80);
  CHECK(got[3].st == 0x80 && got[3].d1 == 62);
  CHECK(got[4].st == 0x99 && got[4].d1 == 36);
  CHECK(got[5].st == 0xC0 && got[5].d1 == 5);
  CHECK(got[6].st == 0x90 && got[6].d1 == 70 && got[6].d2 == 1);

  // --- messages from the Python app
  PixelParser px;
  StripConfig cfg; Colours col;
  uint8_t op = 0;
  for (uint8_t b : CFG_MSG) if (uint8_t o = px.feed(b)) op = o;
  CHECK(op == OP_CONFIG);
  CHECK(parseConfig(px.data(), px.length(), 300, cfg, col));
  CHECK(cfg.numLeds == CFG_NUM_LEDS && cfg.ledsPerMetre == 30 && cfg.lowestPitch == 36 && cfg.numKeys == 61);
  CHECK(cfg.firstLed == -2 && cfg.reversed && col.right[0] == 0x0A && col.left[2] == 0x4E);
  KeyMap km; km.build(cfg);
  int same = 0;
  for (int p = 0; p < 128; p++) same += km.ledForPitch(p) == CFG_LED_FOR_PITCH[p];
  CHECK(same == 128);  // firmware and app agree on every key

  static SongBook book;
  bool done = false;
  for (uint8_t b : SONG_MSGS) if (uint8_t o = px.feed(b)) done |= book.feed(o, px.data(), px.length());
  CHECK(done && book.count == SONG_NOTES);
  CHECK(book.notes[2].pitch == 62 && book.notes[2].ms() == 500 && book.notes[1].hand == 1);
  CHECK(book.notes[SONG_NOTES - 1].ms() == 1500 + 99 * 250);

  // A lost data message keeps the old song.
  static SongBook b2;
  uint8_t beginMsg[] = {0x02, 0x00}, endMsg[] = {0x02, 0x00};
  b2.feed(OP_SONG_BEGIN, beginMsg, 2);
  CHECK(!b2.feed(OP_SONG_END, endMsg, 2) && b2.count == 0);
  // A lost DATA message in the middle is noticed too (the next message starts at the wrong index).
  static SongBook b3;
  int idx = 0; bool ok3 = false;
  for (uint8_t b : SONG_MSGS) if (uint8_t o = px.feed(b)) {
    if (o == OP_SONG_DATA && idx++ == 1) continue;  // drop the 2nd data message
    ok3 |= b3.feed(o, px.data(), px.length());
  }
  CHECK(!ok3 && b3.count == 0);

  // --- song titles, library files and bigger storage (ESP32-S3 PSRAM)
  static SongBook big;
  static SongNote bigNotes[5000], bigStage[5000];
  big.setStorage(bigNotes, bigStage, 5000);
  const char* name = "Für Elise";
  big.feed(OP_SONG_TITLE, (const uint8_t*)name, (int)strlen(name));
  bool ok4 = false;
  for (uint8_t b : SONG_MSGS) if (uint8_t o = px.feed(b)) ok4 |= big.feed(o, px.data(), px.length());
  CHECK(ok4 && big.count == SONG_NOTES && strcmp(big.title, "Für Elise") == 0 && big.capacity == 5000);
  uint8_t head[songfile::HEADER_MAX];
  int hl = songfile::header(big, head);
  char back[TITLE_MAX + 1]; int cnt = 0;
  CHECK(songfile::parse(head, hl, back, cnt) == hl && cnt == SONG_NOTES && strcmp(back, "Für Elise") == 0);
  CHECK(songfile::parse((const uint8_t*)"nope", 4, back, cnt) == 0);
  CHECK(book.title[0] == 0);  // no title sent → empty
  // A song longer than the board holds keeps its beginning (e.g. 105 notes into a 60-note book).
  static SongBook small;
  static SongNote sn[60], ss[60];
  small.setStorage(sn, ss, 60);
  bool ok5 = false;
  for (uint8_t b : SONG_MSGS) if (uint8_t o = px.feed(b)) ok5 |= small.feed(o, px.data(), px.length());
  CHECK(ok5 && small.count == 60 && small.notes[59].ms() == book.notes[59].ms());

  // --- practise without the phone
  Lesson ls;
  ls.begin(book);
  CHECK(ls.running && ls.start == 0 && ls.end == 2 && ls.waiting.has(60) && ls.waiting.has(48));
  CHECK(ls.press(book, 61) == -1);                 // wrong key
  CHECK(ls.press(book, 60) == 0);                  // half the chord
  CHECK(ls.press(book, 48) == 1 && ls.start == 2); // chord done → next note 62
  CHECK(ls.press(book, 62) == 1);
  CHECK(ls.start == 3 && ls.end == 5);             // 64 and 52 start 20 ms apart = one chord
  ls.next(book);                                   // button step
  CHECK(ls.start == 5);
  while (ls.running) ls.next(book);
  CHECK(ls.finished(book));

  // --- ESP32 → app note message
  uint8_t out[16], payload[2] = {60, 90};
  int n = buildMessage(OP_NOTE, payload, 2, out);
  CHECK(n == 6 && out[0] == 0xA5 && out[5] == (OP_NOTE ^ 2 ^ 60 ^ 90));
  KeySet ks; uint8_t e[] = {60, 64};
  ks.set(e, 2);
  CHECK(ks.has(60) && ks.has(64) && !ks.has(62));
  ks.remove(60); ks.remove(64);
  CHECK(ks.empty());

  printf(fails ? "%d FAILED\n" : "all piano tests passed\n", fails);
  return fails != 0;
}
