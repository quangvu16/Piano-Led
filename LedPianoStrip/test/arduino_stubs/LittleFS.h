#pragma once
#include "Arduino.h"
struct File { explicit operator bool() const; size_t write(const uint8_t*, size_t); size_t read(uint8_t*, size_t); void close();
  bool isDirectory(); File openNextFile(); String name(); bool seek(size_t); };
struct LittleFSFS { bool begin(bool); File open(const char*, const char* = "r"); File open(const String&, const char* = "r");
  bool exists(const char*); bool mkdir(const char*); bool remove(const char*); bool remove(const String&); }; extern LittleFSFS LittleFS;
