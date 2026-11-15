// LED Piano – Adalight receiver for Arduino Uno / Nano / Mega (+ WS2812B), with FastLED.
//
// The plain Arduino from the HowToMechatronics FastLED tutorial has no Bluetooth LE or Wi-Fi, so the
// LED Piano apps drive it with the standard "Adalight" protocol (also used by Hyperion and Prismatik):
//     'A' 'd' 'a'  countHi countLo  checksum(countHi ^ countLo ^ 0x55)   then (count + 1) × r g b
// The app sends the whole strip picture every time a key changes (and once a second), so this sketch only
// copies the colours into the strip – `leds[i] = CRGB(r, g, b); FastLED.show();` like the tutorial.
//
// Connect it with:
//   * a USB cable  – Android (USB-OTG), Mac, Python (Windows / Mac / Linux)
//   * an HC-05 / HC-06 Bluetooth module (Bluetooth "Classic") – Android, Mac, Windows; NOT iPhone/iPad
//     (iOS only talks to Bluetooth LE devices). Set the module to 115200 baud first: AT+UART=115200,0,0
//     (HC-05) or AT+BAUD8 (HC-06). Wiring: HC-05 TXD → Arduino RX (D0), HC-05 RXD ← Arduino TX (D1)
//     through a 1 kΩ / 2 kΩ divider (the module's RX is 3.3 V). Unplug it while uploading the sketch.
//
// In the app: Cài đặt → kiểu kết nối "Arduino Uno/Nano – Adalight", then connect to the USB port or the
// paired HC-05.
//
// Wiring of the strip, as in the tutorial: DIN ← 330 Ω ← pin LED_PIN; 5 V supply to the strip with a
// 1000 µF capacitor; supply GND, strip GND and Arduino GND joined.

#include <FastLED.h>

#define LED_PIN     7      // data pin (the tutorial uses 7)
#define NUM_LEDS    177    // LEDs on your strip (Uno: up to ~500 fit in its 2 KB of memory)
#define BRIGHTNESS  255    // the app sets the brightness itself
#define MILLIAMPS   2000   // your 5 V supply's limit (FastLED dims to stay below it)
#define BAUD        115200 // must match the apps (and the HC-05 module)
#define IDLE_OFF_MS 5000   // no picture for this long (app closed) → all LEDs off

CRGB leds[NUM_LEDS];

enum State { WAIT_A, WAIT_D, WAIT_A2, HI, LO, CHK, DATA };
State st = WAIT_A;
uint8_t hi, lo;
uint16_t count, got;  // LEDs in this picture, colour bytes read so far
uint8_t rgb[3];
uint32_t lastFrame = 0, lastHello = 0;

void setup() {
  FastLED.addLeds<WS2812B, LED_PIN, GRB>(leds, NUM_LEDS);
  FastLED.setBrightness(BRIGHTNESS);
  FastLED.setMaxPowerInVoltsAndMilliamps(5, MILLIAMPS);
  // Self-test: the first LEDs of the tutorial example, red, green, blue.
  leds[0] = CRGB(255, 0, 0);
  leds[1] = CRGB(0, 255, 0);
  leds[2] = CRGB(0, 0, 255);
  FastLED.show();
  delay(500);
  FastLED.clear(true);
  Serial.begin(BAUD);
  Serial.print("Ada\n");  // Adalight greeting
}

void loop() {
  while (Serial.available()) {
    uint8_t b = Serial.read();
    switch (st) {
      case WAIT_A:  st = b == 'A' ? WAIT_D : WAIT_A; break;
      case WAIT_D:  st = b == 'd' ? WAIT_A2 : (b == 'A' ? WAIT_D : WAIT_A); break;
      case WAIT_A2: st = b == 'a' ? HI : (b == 'A' ? WAIT_D : WAIT_A); break;
      case HI:      hi = b; st = LO; break;
      case LO:      lo = b; st = CHK; break;
      case CHK:
        if (b == (uint8_t)(hi ^ lo ^ 0x55)) {
          count = ((uint16_t)hi << 8 | lo) + 1;
          got = 0;
          st = DATA;
        } else {
          st = WAIT_A;  // damaged header: look for the next "Ada"
        }
        break;
      case DATA: {
        uint16_t led = got / 3;
        rgb[got % 3] = b;
        got++;
        if (got % 3 == 0 && led < NUM_LEDS) leds[led] = CRGB(rgb[0], rgb[1], rgb[2]);
        if (got >= (uint32_t)count * 3) {
          for (uint16_t i = count; i < NUM_LEDS; i++) leds[i] = CRGB::Black;  // shorter picture: rest off
          FastLED.show();
          lastFrame = millis();
          st = WAIT_A;
        }
        break;
      }
    }
  }
  uint32_t now = millis();
  if (lastFrame && now - lastFrame > IDLE_OFF_MS) {  // the app went away
    FastLED.clear(true);
    lastFrame = 0;
  }
  if (!lastFrame && now - lastHello > 1000) {  // say hello while idle (Adalight custom)
    Serial.print("Ada\n");
    lastHello = now;
  }
}
