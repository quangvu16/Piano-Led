#pragma once
#include "Arduino.h"
enum HTTPMethod { HTTP_GET, HTTP_POST }; enum { UPLOAD_FILE_START, UPLOAD_FILE_WRITE, UPLOAD_FILE_END };
struct HTTPUpload { int status; uint8_t* buf; size_t currentSize; };
struct WebServer { WebServer(int); typedef std::function<void()> H; void on(const char*, HTTPMethod, H); void on(const char*, HTTPMethod, H, H);
  bool authenticate(const char*, const char*); void requestAuthentication(); bool hasArg(const char*); String arg(const char*);
  void send(int, const char* = nullptr, const String& = String()); void sendHeader(const char*, const char*); HTTPUpload& upload(); void begin(); void handleClient(); };
