#pragma once
#define WIFI_POWER_8_5dBm 34
#include "Arduino.h"
struct IPAddress { uint8_t operator[](int) const; String toString() const; };
enum { WIFI_STA, WIFI_AP, WL_CONNECTED };
struct WiFiClass { void setHostname(const char*); void mode(int); void setAutoReconnect(bool); bool setTxPower(int); void begin(const char*, const char*); int status();
  IPAddress localIP(); IPAddress softAPIP(); String SSID(); bool softAP(const char*, const char*); }; extern WiFiClass WiFi;
struct WiFiClient { explicit operator bool(); bool connected(); void stop(); void setNoDelay(bool); int available(); int read();
  size_t write(const uint8_t*, size_t); };
struct WiFiServer { WiFiServer(int); bool hasClient(); WiFiClient available(); void begin(); void setNoDelay(bool); };
