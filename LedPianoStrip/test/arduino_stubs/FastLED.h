#pragma once
#include "Arduino.h"
struct CHSV { CHSV(uint8_t, uint8_t, uint8_t); };
struct CRGB { uint8_t r, g, b; CRGB(); CRGB(uint8_t, uint8_t, uint8_t); CRGB(const CHSV&); CRGB& operator=(const CHSV&);
  CRGB& nscale8_video(uint8_t); enum { Black, Red, Green }; CRGB(int); };
void fill_solid(CRGB*, int, const CRGB&); uint8_t qadd8(uint8_t, uint8_t);
struct WS2812B {}; enum { GRB };
struct CFastLED { template<class T, int P, int O> void addLeds(CRGB*, int); void setBrightness(uint8_t); void show();
  void setMaxPowerInVoltsAndMilliamps(int, int); void clear(bool = false); }; extern CFastLED FastLED;
