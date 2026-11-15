#pragma once
#include "Arduino.h"
struct Preferences { bool begin(const char*, bool); size_t getBytes(const char*, void*, size_t); size_t putBytes(const char*, const void*, size_t);
  uint8_t getUChar(const char*, uint8_t); void putUChar(const char*, uint8_t); int getInt(const char*, int); void putInt(const char*, int);
  String getString(const char*, String); void putString(const char*, String); };
