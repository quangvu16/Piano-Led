#pragma once
#include "Arduino.h"
struct BLEUUID { BLEUUID(const char*); };
struct BLECharacteristic; struct BLEServer; struct BLEClient; struct BLEAdvertisedDevice; struct BLERemoteCharacteristic;
struct BLECharacteristicCallbacks { virtual void onWrite(BLECharacteristic*); virtual ~BLECharacteristicCallbacks(); };
struct BLEDescriptor {}; struct BLE2902 : BLEDescriptor {};
struct BLECharacteristic { enum { PROPERTY_READ = 1, PROPERTY_WRITE = 2, PROPERTY_WRITE_NR = 4, PROPERTY_NOTIFY = 8 };
  String getValue(); void setValue(uint8_t*, size_t); void notify(); void addDescriptor(BLEDescriptor*); void setCallbacks(BLECharacteristicCallbacks*); };
struct BLEService { BLECharacteristic* createCharacteristic(const char*, uint32_t); void start(); };
struct BLEServerCallbacks { virtual void onConnect(BLEServer*); virtual void onDisconnect(BLEServer*); virtual ~BLEServerCallbacks(); };
struct BLEServer { void setCallbacks(BLEServerCallbacks*); BLEService* createService(const char*); uint32_t getConnectedCount(); };
struct BLEAdvertisementData { void setFlags(uint8_t); void setName(String); void setCompleteServices(BLEUUID); };
struct BLEAdvertising { void setAdvertisementData(BLEAdvertisementData&); void setScanResponseData(BLEAdvertisementData&); };
struct BLEAdvertisedDevice { bool haveServiceUUID(); bool isAdvertisingService(BLEUUID); bool haveName(); String getName(); };
struct BLEAdvertisedDeviceCallbacks { virtual void onResult(BLEAdvertisedDevice); virtual ~BLEAdvertisedDeviceCallbacks(); };
struct BLEScanResults {};
struct BLEScan { void setAdvertisedDeviceCallbacks(BLEAdvertisedDeviceCallbacks*, bool); void setActiveScan(bool); void setInterval(int); void setWindow(int);
  bool start(uint32_t, void (*)(BLEScanResults), bool); BLEScanResults* start(uint32_t, bool = false); void stop(); void clearResults(); };
typedef std::function<void(BLERemoteCharacteristic*, uint8_t*, size_t, bool)> notify_callback;
struct BLERemoteCharacteristic { bool canNotify(); void registerForNotify(notify_callback, bool = true, bool = true); };
struct BLERemoteService { BLERemoteCharacteristic* getCharacteristic(BLEUUID); };
struct BLEClientCallbacks { virtual void onConnect(BLEClient*); virtual void onDisconnect(BLEClient*); virtual ~BLEClientCallbacks(); };
struct BLEClient { void setClientCallbacks(BLEClientCallbacks*); bool connect(BLEAdvertisedDevice*); BLERemoteService* getService(BLEUUID); void disconnect(); };
struct BLEDevice { static void init(String); static void setMTU(int); static BLEServer* createServer(); static BLEClient* createClient();
  static BLEScan* getScan(); static BLEAdvertising* getAdvertising(); static void startAdvertising(); };
