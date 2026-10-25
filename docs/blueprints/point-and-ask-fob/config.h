// firmware/whoisthis_xiao_sense/config.h overrides for the point-and-ask fob build.
#pragma once
#define WIT_MODEL "WhoIsThis fob"
#define WIT_CAPTURE_ON_TAP 1    // press-to-ask: a picture only on a press, no stream server, radio and camera asleep between
#define WIT_BUTTON_PIN 2        // D1: tail button (tap = one lookup, long press = stop)
#define WIT_BATTERY_PIN 1       // A0: 220k/220k divider from BAT+
#define WIT_BATTERY_DIVIDER 2.0f
#define WIT_LED_PIN 21
#define WIT_OLED 0
#define WIT_BUZZER_PIN 3        // D2: passive buzzer chirps when a label arrives (optional)
#define WIT_JPEG_QUALITY 12
