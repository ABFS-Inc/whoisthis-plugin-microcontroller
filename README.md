# whoisthis-plugin-microcontroller

Companion plugin for the [WhoIsThis](https://github.com/ABFS-Inc/whoisthis) Android app: drives a
**Seeed Studio XIAO ESP32-S3 Sense** (or any board running the firmware in `firmware/`) over the
local Wi-Fi network and exposes it to the app through the
[`online.whoisthis:capture-contract`](https://github.com/ABFS-Inc/whoisthis-capture-contract) AIDL
service. The sibling [`whoisthis-plugin-xg-glasses`](https://github.com/ABFS-Inc/whoisthis-plugin-xg-glasses)
does the same for camera glasses; this repository adds a self-built wearable or desk camera.

```
XIAO ESP32-S3 Sense ──Wi-Fi/HTTP (LAN)──▶ Engine ──▶ CaptureDeviceService (ICaptureDevice) ──▶ WhoIsThis app
     firmware/                               app/
```

The board streams MJPEG (or serves stills) to the plugin on the same network; the plugin copies the
JPEGs into the contract's two-slot shared-memory ring (or a pipe for stills) for the WhoIsThis app
on the same phone. Nothing leaves the phone; the plugin declares no internet domains.

## Layout

| Path | Role |
|------|------|
| `app/src/main/kotlin/.../CaptureDeviceService.kt` | Binder service; every call verifies the caller is `online.whoisthis.whoisthis` signed with the pinned key (`trustedCallerSha256` in `gradle.properties`). Debug builds skip the signer check, never the package check. |
| `Engine.kt` | One capture at a time: `GET /whoisthis` → `CONNECTED` with the board's confirmed capabilities, then MJPEG into the ring (reconnecting on drops), stills over a pipe, or (fob firmware, `capture=on_tap`) one still per button press by long-polling `/capture?wait=`; polls `/events` for `tap` / `long_press` / `battery`; `present()` → `POST /present` (OLED text, LED blink, buzzer). No Activity is needed: a start from the app runs straight away. |
| `Protocol.kt`, `Board.kt` | The firmware's HTTP protocol (status/events JSON, MJPEG part reader, JPEG size from the SOF marker) and the `HttpURLConnection` transport behind a test seam. |
| `Discovery.kt`, `Devices.kt` | mDNS (`_whoisthis._tcp`) discovery through Android NSD, plus one typed address; boards → contract `DeviceInfo` (the host is the device id). |
| `PluginActivity.kt` | Plugin screen: discovered boards, add by address, connect/disconnect, capture facts and the latest frame (in memory only), what was sent to the board. |
| `firmware/` | Arduino sketch for the XIAO ESP32-S3 Sense: Wi-Fi provisioning portal, mDNS, `/whoisthis`, `/capture`, `:81/stream`, `/events`, `/present`. See `firmware/README.md` for the protocol and wiring. |
| `docs/blueprints/` | Physical design: measurements of the bought parts, the catalogue of form factors (personas, fabrication methods), and three built designs (lapel badge / pendant, cap-brim clip, point-and-ask fob) with OpenSCAD models, STLs, dimensioned drawings, BOM, wiring and firmware config. |
| `.github/workflows/release.yml` | Merge to `main` → signed APK + firmware binary, GitHub Release, `whoisthis.online/download/plugins/microcontroller/latest.json`. |

## Build

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Release builds read `key.properties` (gitignored) with `storeFile`, `storePassword`, `keyPassword`,
`keyAlias`. CI creates it from the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS` secrets (use a keystore of its own, not the glasses
plugin's); publishing needs `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`.

## Using it

1. Flash the firmware and join the board to your Wi-Fi (`firmware/README.md`).
2. Install the plugin APK; open WhoIsThis → Plugins → Microcontroller. The board shows up by mDNS
   as `whoisthis-cam-XXXX`; if your network blocks multicast, open the plugin screen and type its IP.
3. Start it from the app like any other capture source. Tap the board's button to trigger a lookup,
   long-press to stop; the LED blinks when the app presents a label (and the OLED shows it when fitted).
   A board built with `WIT_CAPTURE_ON_TAP` (the fob) sends a picture only when its button is pressed;
   the plugin screen says so when it connects.

Device states and detail strings follow the contract (`connecting`, `connected <caps>`,
`streaming` / `streaming stills` / `streaming on_tap`, `error <reason>`); the plugin screen's status line and the line
under the device in the WhoIsThis app carry the same text. `adb logcat -s wit.mcu` has the stack trace.

## Trust

The WhoIsThis app pins each plugin's release signer in `GlassesPlugin.TRUSTED` and today binds one
plugin at a time, so shipping this plugin needs two follow-ups in the app repository: add
`online.whoisthis.plugin.microcontroller` with this repo's release signer SHA-256 to the pin list,
and let the plugins screen choose among installed plugins. Until then debug builds of the app bind
unverified plugins.
