#pragma once
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <string>
#include <functional>
#define F(x) x
#define LOW 0
#define INPUT_PULLUP 2
#define HIGH 1
#define OUTPUT 3
void digitalWrite(int, int);
typedef int BaseType_t; typedef void* QueueHandle_t;
#define pdTRUE 1
QueueHandle_t xQueueCreate(int, int); BaseType_t xQueueSend(QueueHandle_t, const void*, int); BaseType_t xQueueReceive(QueueHandle_t, void*, int);
uint32_t millis(); void delay(uint32_t); int digitalRead(int); void pinMode(int, int);
template<class T> T constrain(T x, T a, T b) { return x < a ? a : x > b ? b : x; }
class String { public: String(); String(const char*); String(int); String(unsigned); String(float);
  size_t length() const; const char* c_str() const; char operator[](size_t) const; char& operator[](size_t);
  String& operator+=(const String&); String& operator+=(const char*); String& operator+=(char);
  bool startsWith(const String&) const; bool endsWith(const char*) const; int lastIndexOf(char) const; String substring(int) const; int toInt() const; bool operator!=(const String&) const; bool operator==(const char*) const; };
String operator+(const String&, const String&); String operator+(const char*, const String&); String operator+(const String&, const char*);
String operator+(const String&, int);
struct HardwareSerial { void begin(int); int available(); int read(); void println(const char* = ""); void println(const String&); void print(char);
  void printf(const char*, ...); size_t write(const uint8_t*, size_t); size_t print(const char*); };
extern HardwareSerial Serial; extern HardwareSerial Serial0;
void* ps_malloc(size_t); bool psramFound();
void neopixelWrite(uint8_t, uint8_t, uint8_t, uint8_t);
struct EspClass { uint64_t getEfuseMac(); void restart(); }; extern EspClass ESP;
