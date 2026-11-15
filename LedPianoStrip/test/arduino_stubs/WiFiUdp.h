#pragma once
#include "Arduino.h"
#include "WiFi.h"
struct WiFiUDP { void begin(int); int parsePacket(); int read(uint8_t*, size_t); IPAddress remoteIP(); uint16_t remotePort();
  int beginPacket(IPAddress, uint16_t); size_t print(const char*); int endPacket(); };
