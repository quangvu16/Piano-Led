#pragma once
struct MDNSClass { bool begin(const char*); void setInstanceName(const char*); void addService(const char*, const char*, int);
  void addServiceTxt(const char*, const char*, const char*, const char*); }; extern MDNSClass MDNS;
