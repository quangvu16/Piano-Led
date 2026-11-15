#include "../LedPianoStrip/KeyMap.h"
#include <cstdio>
#include <set>
using namespace ledpiano;
static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)

static void feedAll(FrameParser& p, std::initializer_list<int> bytes, int& frames) {
  for (int b : bytes) if (p.feed((uint8_t)b)) frames++;
}

int main() {
  // --- parser
  FrameParser p; int frames = 0;
  // One frame cut across two BLE writes: right hand C4 (byte 49) white, left hand C3 (byte 61) white.
  feedAll(p, {0x7F, 49, 1, 61}, frames); CHECK(frames == 0);
  feedAll(p, {3, 0x6E}, frames);         CHECK(frames == 1);
  CHECK(p.count() == 2 && p.key(0).key == 49 && p.key(0).state == 1 && p.key(1).key == 61 && p.key(1).state == 3);
  // Reset frame from the apps = all off.
  feedAll(p, {0x7F, 0x01, 0x00, 0x6E}, frames); CHECK(frames == 2 && p.count() == 0);
  // Lost end byte: a new start recovers.
  feedAll(p, {0x7F, 10, 1, 0x7F, 20, 2, 0x6E}, frames); CHECK(frames == 3 && p.count() == 1 && p.key(0).key == 20);
  // Garbage before a frame is ignored; out-of-range key dropped.
  feedAll(p, {5, 6, 0x7F, 200, 1, 30, 4, 0x6E}, frames); CHECK(frames == 4 && p.count() == 1 && p.key(0).key == 30);

  // --- key byte <-> note (app setting "Organ key" = A0)
  CHECK(KeyMap::pitchForKeyByte(88) == 21);   // A0
  CHECK(KeyMap::pitchForKeyByte(1) == 108);   // C8
  CHECK(KeyMap::pitchForKeyByte(49) == 60);   // middle C

  // --- geometry
  CHECK(isBlack(61) && !isBlack(60) && isBlack(22) && !isBlack(21));
  // C4 to C5 is exactly 7 white keys.
  CHECK(fabsf(keyCentreMm(72, 21, 23.5f) - keyCentreMm(60, 21, 23.5f) - 7 * 23.5f) < 0.01f);

  const float densities[] = {144, 96, 74, 60, 30};
  for (float d : densities) {
    StripConfig c; c.ledsPerMetre = d; c.numLeds = (int)(52 * 23.5f * d / 1000) + 2;
    KeyMap m; m.build(c);
    std::set<int> used; int missing = 0, dup = 0, prev = -1, order = 1;
    for (int pch = 21; pch <= 108; pch++) {
      int led = m.ledForPitch(pch);
      if (led < 0) { missing++; continue; }
      if (!used.insert(led).second) dup++;
      if (led < prev) order = 0;
      prev = led;
    }
    printf("%3.0f LEDs/m: strip %3d LEDs, keys without LED %d, keys sharing an LED %d, left-to-right %s\n",
           d, c.numLeds, missing, dup, order ? "yes" : "NO");
    CHECK(missing == 0 && order);
    if (d >= 96) CHECK(dup == 0);
  }
  // 61-key keyboard (C2..C7), 144/m, strip reversed: lowest key is at the far end.
  StripConfig c61; c61.lowestPitch = 36; c61.numKeys = 61; c61.numLeds = 130; c61.reversed = true;
  KeyMap k61; k61.build(c61);
  CHECK(k61.ledForPitch(36) > k61.ledForPitch(96));
  CHECK(k61.ledForPitch(35) == -1 && k61.ledForPitch(97) == -1);
  printf("A0 -> LED %d, middle C -> LED %d, C8 -> LED %d (88 keys, 144/m)\n",
         [] { StripConfig c; c.numLeds = 177; KeyMap m; m.build(c); return m.ledForPitch(21); }(),
         [] { StripConfig c; c.numLeds = 177; KeyMap m; m.build(c); return m.ledForPitch(60); }(),
         [] { StripConfig c; c.numLeds = 177; KeyMap m; m.build(c); return m.ledForPitch(108); }());
  // --- WS2812B pixel messages (what the apps send in WS2812B mode)
  {
    PixelParser px;
    // Tutorial pixels: leds[0] red, leds[19] green-ish, leds[300] (index > 255) blue.
    uint8_t pay[] = {0, 0, 255, 0, 0,  19, 0, 50, 255, 20,  44, 1, 0, 0, 255};
    uint8_t chk = OP_PIXELS ^ (uint8_t)sizeof(pay);
    for (uint8_t b : pay) chk ^= b;
    uint8_t got = 0;
    got |= px.feed(0xA5); got |= px.feed(OP_PIXELS); got |= px.feed(sizeof(pay));
    for (uint8_t b : pay) got |= px.feed(b);
    CHECK(got == 0);
    CHECK(px.feed(chk) == OP_PIXELS);
    CHECK(px.count() == 3 && px.pixel(0).index == 0 && px.pixel(0).r == 255);
    CHECK(px.pixel(1).index == 19 && px.pixel(1).g == 255 && px.pixel(2).index == 300 && px.pixel(2).b == 255);
    // Damaged checksum: ignored.
    uint8_t bad[] = {0xA5, OP_BRIGHTNESS, 1, 80, (uint8_t)(OP_BRIGHTNESS ^ 1 ^ 80 ^ 0xFF)};
    uint8_t r = 0; for (uint8_t b : bad) r |= px.feed(b);
    CHECK(r == 0 && !px.active());
    uint8_t ok[] = {0xA5, OP_BRIGHTNESS, 1, 80, (uint8_t)(OP_BRIGHTNESS ^ 1 ^ 80)};
    r = 0; for (uint8_t b : ok) r = px.feed(b);
    CHECK(r == OP_BRIGHTNESS && px.brightness() == 80);
    // All-off = PIXELS with no pixels.
    uint8_t off[] = {0xA5, OP_PIXELS, 0, OP_PIXELS};
    r = 0; for (uint8_t b : off) r = px.feed(b);
    CHECK(r == OP_PIXELS && px.count() == 0);
    // A payload containing 0x7F / 0x6E bytes is still a pixel message.
    uint8_t tricky[] = {0x7F, 0, 0x6E, 0x7F, 0x6E};
    uint8_t c2 = OP_PIXELS ^ 5; for (uint8_t b : tricky) c2 ^= b;
    px.feed(0xA5); px.feed(OP_PIXELS); px.feed(5); for (uint8_t b : tricky) px.feed(b);
    CHECK(px.feed(c2) == OP_PIXELS && px.pixel(0).index == 0x7F && px.pixel(0).g == 0x7F);
  }
  printf(fails ? "%d FAILED\n" : "all tests passed\n", fails);
  return fails != 0;
}
