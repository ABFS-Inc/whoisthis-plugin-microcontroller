// WhoIsThis firmware for the Seeed XIAO ESP32-S3 Sense.
//
// Turns the board into a capture device for the WhoIsThis Microcontroller plugin
// (online.whoisthis.plugin.microcontroller). Everything is plain HTTP on the local network:
//
//   GET  /whoisthis         JSON: id, model, firmware, protocol, capabilities[], battery, stream_port
//   GET  /capture?max=N     one JPEG, long side <= N
//   GET  :81/stream?max=N   multipart/x-mixed-replace MJPEG, Content-Length on every part
//   GET  /events?after=N    {"next":M,"events":[{"seq","name","detail"}]}  (tap, long_press, battery)
//   POST /present           {"text","show","speak"}: OLED text (if fitted), LED blink, buzzer chirp
//   GET  /                  human status page;  POST /wifi  ssid=&pass=  saves credentials, restarts
//
// Discovery: mDNS _whoisthis._tcp on the HTTP port, TXT id/model/caps/stream_port/fw.
// Frames go to whoever asks on the LAN; the plugin is the only intended client. Nothing leaves it.

#include <WiFi.h>
#include <ESPmDNS.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <Wire.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "config.h"
#include "camera_pins.h"

#if WIT_OLED
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
static Adafruit_SSD1306 oled(128, 64, &Wire, -1);
#endif

// ---------------------------------------------------------------- identity

static String g_id;        // "xiao-ab12"
static String g_hostname;  // "whoisthis-cam-ab12"
static bool g_cameraOk = false;
static bool g_oledOk = false;
static bool g_apMode = false;
static Preferences g_prefs;
static DNSServer g_dns;

static String macSuffix() {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char s[5];
  snprintf(s, sizeof s, "%02x%02x", mac[4], mac[5]);
  return String(s);
}

// ---------------------------------------------------------------- events

struct Event { uint32_t seq; const char* name; char detail[12]; };
static const int EVENT_RING = 16;
static Event g_events[EVENT_RING];
static uint32_t g_eventSeq = 0;
static portMUX_TYPE g_eventMux = portMUX_INITIALIZER_UNLOCKED;

static void pushEvent(const char* name, const char* detail) {
  portENTER_CRITICAL(&g_eventMux);
  Event& e = g_events[g_eventSeq % EVENT_RING];
  e.seq = ++g_eventSeq;
  e.name = name;
  strlcpy(e.detail, detail, sizeof e.detail);
  portEXIT_CRITICAL(&g_eventMux);
}

// ---------------------------------------------------------------- battery

static int g_battery = -1;  // percent, -1 = no sense

static int lipoPercent(float v) {
  // Coarse open-circuit LiPo curve; good enough for a battery event every 30 s.
  static const float vs[] = {3.30f, 3.50f, 3.60f, 3.70f, 3.75f, 3.80f, 3.90f, 4.00f, 4.10f, 4.20f};
  static const int ps[] = {0, 5, 10, 25, 40, 55, 70, 85, 95, 100};
  if (v <= vs[0]) return 0;
  for (int i = 1; i < 10; i++)
    if (v <= vs[i]) return ps[i - 1] + (int)((v - vs[i - 1]) / (vs[i] - vs[i - 1]) * (ps[i] - ps[i - 1]));
  return 100;
}

static void sampleBattery(bool force) {
#if WIT_BATTERY_PIN >= 0
  static uint32_t last = 0;
  if (!force && millis() - last < WIT_BATTERY_PERIOD_MS) return;
  last = millis();
  uint32_t mv = 0;
  for (int i = 0; i < 8; i++) mv += analogReadMilliVolts(WIT_BATTERY_PIN);
  float v = (mv / 8) / 1000.0f * WIT_BATTERY_DIVIDER;
  int p = lipoPercent(v);
  if (force || abs(p - g_battery) >= 5) {
    g_battery = p;
    char d[12];
    snprintf(d, sizeof d, "%d", p);
    pushEvent("battery", d);
  }
#endif
}

// ---------------------------------------------------------------- button

static void pollButton() {
#if WIT_BUTTON_PIN >= 0
  static bool down = false;
  static uint32_t downAt = 0;
  static bool longSent = false;
  bool pressed = digitalRead(WIT_BUTTON_PIN) == LOW;
  uint32_t now = millis();
  if (pressed && !down) { down = true; downAt = now; longSent = false; }
  else if (pressed && down && !longSent && now - downAt >= WIT_LONG_PRESS_MS) { longSent = true; pushEvent("long_press", ""); }
  else if (!pressed && down) {
    down = false;
    if (!longSent && now - downAt >= 30) pushEvent("tap", "1");  // 30 ms debounce
  }
#endif
}

// ---------------------------------------------------------------- output (present)

static uint32_t g_blinkUntil = 0;
static String g_shown;

static void pollLed() {
  if (WIT_LED_PIN < 0) return;
  uint32_t now = millis();
  if (now < g_blinkUntil) digitalWrite(WIT_LED_PIN, ((now / 120) % 2) ? HIGH : LOW);  // 120 ms on/off
  else digitalWrite(WIT_LED_PIN, HIGH);                                               // off (active LOW)
}

static void showText(const String& text) {
  g_shown = text;
#if WIT_OLED
  if (!g_oledOk) return;
  oled.clearDisplay();
  oled.setTextSize(text.length() > 20 ? 1 : 2);
  oled.setCursor(0, 0);
  oled.setTextWrap(true);
  oled.print(text);
  oled.display();
#endif
}

static void present(const String& text, bool show, bool speak) {
  if (show) showText(text);
  g_blinkUntil = millis() + 480;  // two blinks
#if WIT_BUZZER_PIN >= 0
  if (speak) { tone(WIT_BUZZER_PIN, 1760, 60); }
#endif
}

// ---------------------------------------------------------------- camera

static framesize_t framesizeFor(int maxLong) {
  if (maxLong <= 320) return FRAMESIZE_QVGA;    // 320x240
  if (maxLong <= 640) return FRAMESIZE_VGA;     // 640x480
  if (maxLong <= 800) return FRAMESIZE_SVGA;    // 800x600
  if (maxLong <= 1024) return FRAMESIZE_XGA;    // 1024x768
  if (maxLong <= 1280) return FRAMESIZE_HD;     // 1280x720
  return FRAMESIZE_UXGA;                        // 1600x1200
}

static framesize_t g_framesize = FRAMESIZE_HD;

static bool initCamera() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM; c.pin_d1 = Y3_GPIO_NUM; c.pin_d2 = Y4_GPIO_NUM; c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM; c.pin_d5 = Y7_GPIO_NUM; c.pin_d6 = Y8_GPIO_NUM; c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM; c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_UXGA;  // allocate for the largest; we switch down at runtime
  c.jpeg_quality = WIT_JPEG_QUALITY;
  c.fb_count = psramFound() ? 2 : 1;
  c.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (esp_camera_init(&c) != ESP_OK) return false;
  sensor_t* s = esp_camera_sensor_get();
  if (s) {
    if (s->id.PID == OV3660_PID) { s->set_vflip(s, 1); s->set_brightness(s, 1); s->set_saturation(s, -2); }
    s->set_framesize(s, g_framesize);
  }
  return true;
}

static void applyMax(int maxLong) {
  if (maxLong <= 0) maxLong = 1280;
  if (maxLong > WIT_MAX_LONG_SIDE) maxLong = WIT_MAX_LONG_SIDE;
  framesize_t f = framesizeFor(maxLong);
  if (f == g_framesize) return;
  sensor_t* s = esp_camera_sensor_get();
  if (s && s->set_framesize(s, f) == 0) g_framesize = f;
}

static int queryInt(httpd_req_t* req, const char* key, int dflt) {
  char buf[64], val[16];
  if (httpd_req_get_url_query_str(req, buf, sizeof buf) != ESP_OK) return dflt;
  if (httpd_query_key_value(buf, key, val, sizeof val) != ESP_OK) return dflt;
  return atoi(val);
}

// ---------------------------------------------------------------- JSON helpers

static String capabilitiesJson() {
  String j = "[\"camera.still\",\"camera.stream\"";
  if (WIT_BUTTON_PIN >= 0) j += ",\"input.tap\",\"input.long_press\"";
  if (WIT_BATTERY_PIN >= 0) j += ",\"battery\"";
  if (g_oledOk) j += ",\"display.text\"";
  return j + "]";
}

static String capabilitiesTxt() {
  String t = "camera.still,camera.stream";
  if (WIT_BUTTON_PIN >= 0) t += ",input.tap,input.long_press";
  if (WIT_BATTERY_PIN >= 0) t += ",battery";
  if (g_oledOk) t += ",display.text";
  return t;
}

static String jsonEscape(const String& s) {
  String o;
  for (size_t i = 0; i < s.length(); i++) {
    char ch = s[i];
    if (ch == '"' || ch == '\\') { o += '\\'; o += ch; }
    else if (ch == '\n') o += "\\n";
    else if ((uint8_t)ch < 0x20) continue;
    else o += ch;
  }
  return o;
}

// Minimal extraction of a string / bool field from a flat JSON object; enough for /present.
static String jsonString(const String& body, const char* key) {
  String k = String("\"") + key + "\"";
  int i = body.indexOf(k);
  if (i < 0) return "";
  i = body.indexOf(':', i + k.length());
  if (i < 0) return "";
  i = body.indexOf('"', i);
  if (i < 0) return "";
  String out;
  for (size_t p = i + 1; p < body.length(); p++) {
    char ch = body[p];
    if (ch == '\\' && p + 1 < body.length()) {
      char n = body[++p];
      if (n == 'n') out += '\n'; else if (n == 'u') { p += 4; out += '?'; } else out += n;
    } else if (ch == '"') break;
    else out += ch;
  }
  return out;
}

static bool jsonBool(const String& body, const char* key, bool dflt) {
  String k = String("\"") + key + "\"";
  int i = body.indexOf(k);
  if (i < 0) return dflt;
  i = body.indexOf(':', i + k.length());
  if (i < 0) return dflt;
  String rest = body.substring(i + 1);
  rest.trim();
  return rest.startsWith("true") ? true : rest.startsWith("false") ? false : dflt;
}

// ---------------------------------------------------------------- HTTP handlers (control, :80)

static esp_err_t handleStatus(httpd_req_t* req) {
  String j = "{\"id\":\"" + g_id + "\",\"model\":\"" WIT_MODEL "\",\"firmware\":\"" WIT_FIRMWARE_VERSION "\",\"protocol\":" +
             String(WIT_PROTOCOL_VERSION) + ",\"capabilities\":" + capabilitiesJson() + ",\"battery\":" +
             (g_battery >= 0 ? String(g_battery) : String("null")) + ",\"stream_port\":" + String(WIT_STREAM_PORT) +
             ",\"camera\":" + (g_cameraOk ? "true" : "false") + ",\"rssi\":" + String(WiFi.RSSI()) +
             ",\"uptime_s\":" + String(millis() / 1000) + "}";
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, j.c_str(), j.length());
}

static esp_err_t handleCapture(httpd_req_t* req) {
  if (!g_cameraOk) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "camera not initialised");
  applyMax(queryInt(req, "max", 1280));
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "capture failed");
  httpd_resp_set_type(req, "image/jpeg");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  esp_err_t r = httpd_resp_send(req, (const char*)fb->buf, fb->len);
  esp_camera_fb_return(fb);
  return r;
}

static esp_err_t handleEvents(httpd_req_t* req) {
  uint32_t after = (uint32_t)queryInt(req, "after", 0);
  String j = "{\"next\":";
  String list;
  uint32_t last = after;
  portENTER_CRITICAL(&g_eventMux);
  uint32_t newest = g_eventSeq;
  uint32_t oldest = newest > EVENT_RING ? newest - EVENT_RING + 1 : 1;
  for (uint32_t s = max(after + 1, oldest); s <= newest; s++) {
    Event& e = g_events[s % EVENT_RING];
    if (list.length()) list += ',';
    list += "{\"seq\":" + String(e.seq) + ",\"name\":\"" + e.name + "\",\"detail\":\"" + e.detail + "\"}";
    last = e.seq;
  }
  portEXIT_CRITICAL(&g_eventMux);
  j += String(max(last, newest)) + ",\"events\":[" + list + "]}";
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, j.c_str(), j.length());
}

static esp_err_t readBody(httpd_req_t* req, String& out, size_t limit) {
  size_t remaining = req->content_len;
  if (remaining > limit) return ESP_FAIL;
  char buf[256];
  while (remaining > 0) {
    int r = httpd_req_recv(req, buf, min(remaining, sizeof buf));
    if (r <= 0) return ESP_FAIL;
    out.concat(buf, r);
    remaining -= r;
  }
  return ESP_OK;
}

static esp_err_t handlePresent(httpd_req_t* req) {
  String body;
  if (readBody(req, body, 2048) != ESP_OK) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "body");
  String text = jsonString(body, "text");
  present(text, jsonBool(body, "show", true), jsonBool(body, "speak", true));
  httpd_resp_set_type(req, "application/json");
  return httpd_resp_sendstr(req, "{\"ok\":true}");
}

static String htmlEscape(const String& s) {
  String o;
  for (size_t i = 0; i < s.length(); i++) {
    char ch = s[i];
    if (ch == '<') o += "&lt;"; else if (ch == '&') o += "&amp;"; else if (ch == '"') o += "&quot;"; else o += ch;
  }
  return o;
}

static esp_err_t handleRoot(httpd_req_t* req) {
  String h = "<!doctype html><meta name=viewport content='width=device-width'><title>" + g_hostname + "</title>"
             "<body style='font-family:system-ui;max-width:32em;margin:2em auto'><h2>WhoIsThis camera " + g_hostname + "</h2>";
  if (g_apMode) {
    h += "<p>Not on a Wi-Fi network yet. Enter your network below; the board restarts and joins it.</p>";
  } else {
    h += "<p>On <b>" + WiFi.SSID() + "</b> as <code>" + WiFi.localIP().toString() + "</code>, RSSI " + String(WiFi.RSSI()) +
         " dBm. Camera " + (g_cameraOk ? "ready" : "<b>failed</b>") + ". Capabilities: <code>" + capabilitiesTxt() + "</code>.</p>"
         "<p><a href='/capture?max=640'>Still</a> · <a href='http://" + WiFi.localIP().toString() + ":" + String(WIT_STREAM_PORT) +
         "/stream?max=640'>Stream</a> · <a href='/whoisthis'>Status JSON</a></p>"
         "<p>Last shown: <code>" + htmlEscape(g_shown) + "</code></p>";
  }
  h += "<form method=post action='/wifi'><p><label>Wi-Fi name <input name=ssid required></label></p>"
       "<p><label>Password <input name=pass type=password></label></p><p><button>Save and restart</button></p></form></body>";
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  return httpd_resp_send(req, h.c_str(), h.length());
}

static String formField(const String& body, const char* key) {
  String k = String(key) + "=";
  int i = body.indexOf(k);
  if (i < 0 || (i > 0 && body[i - 1] != '&')) return "";
  int e = body.indexOf('&', i);
  String v = body.substring(i + k.length(), e < 0 ? body.length() : e);
  v.replace('+', ' ');
  String o;
  for (size_t p = 0; p < v.length(); p++) {
    if (v[p] == '%' && p + 2 < v.length()) { o += (char)strtol(v.substring(p + 1, p + 3).c_str(), nullptr, 16); p += 2; }
    else o += v[p];
  }
  return o;
}

static esp_err_t handleWifi(httpd_req_t* req) {
  String body;
  if (readBody(req, body, 512) != ESP_OK) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "body");
  String ssid = formField(body, "ssid"), pass = formField(body, "pass");
  if (ssid.isEmpty()) return httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "ssid");
  g_prefs.begin("wit", false);
  g_prefs.putString("ssid", ssid);
  g_prefs.putString("pass", pass);
  g_prefs.end();
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  httpd_resp_sendstr(req, "<!doctype html><p>Saved. Restarting and joining the network; look for the board in the WhoIsThis plugin.</p>");
  delay(500);
  ESP.restart();
  return ESP_OK;
}

// Captive portal: any unknown URL in AP mode lands on the Wi-Fi form.
static esp_err_t handleNotFound(httpd_req_t* req, httpd_err_code_t) {
  if (g_apMode) {
    httpd_resp_set_status(req, "302 Found");
    httpd_resp_set_hdr(req, "Location", "http://192.168.4.1/");
    return httpd_resp_send(req, nullptr, 0);
  }
  return httpd_resp_send_err(req, HTTPD_404_NOT_FOUND, "not found");
}

// ---------------------------------------------------------------- HTTP handler (stream, :81)

static const char* STREAM_BOUNDARY = "whoisthisframe";

static esp_err_t handleStream(httpd_req_t* req) {
  if (!g_cameraOk) return httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "camera not initialised");
  applyMax(queryInt(req, "max", 1280));
  String ct = String("multipart/x-mixed-replace;boundary=") + STREAM_BOUNDARY;
  httpd_resp_set_type(req, ct.c_str());
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  char head[128];
  unsigned frames = 0;  // timing log every 100th frame: sensor grab time and send time
  while (true) {
    uint32_t tGrab = millis();
    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) return ESP_FAIL;
    uint32_t tSend = millis();
    int n = snprintf(head, sizeof head, "--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\nX-Timestamp: %lu\r\n\r\n",
                     STREAM_BOUNDARY, (unsigned)fb->len, (unsigned long)tSend);
    esp_err_t r = httpd_resp_send_chunk(req, head, n);
    if (r == ESP_OK) r = httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len);
    if (r == ESP_OK) r = httpd_resp_send_chunk(req, "\r\n", 2);
    size_t len = fb->len;
    esp_camera_fb_return(fb);
    if (r != ESP_OK) return r;  // client went away
    frames++;
    if (frames == 1 || frames % 100 == 0) {
      Serial.printf("wit.timing stream.frame n=%u fb_ms=%lu send_ms=%lu bytes=%u\n", frames,
                    (unsigned long)(tSend - tGrab), (unsigned long)(millis() - tSend), (unsigned)len);
    }
  }
}

// ---------------------------------------------------------------- servers

static httpd_handle_t g_control = nullptr;
static httpd_handle_t g_stream = nullptr;

static void startServers() {
  httpd_config_t c = HTTPD_DEFAULT_CONFIG();
  c.server_port = WIT_HTTP_PORT;
  c.ctrl_port = 32768;
  c.max_uri_handlers = 8;
  c.lru_purge_enable = true;
  if (httpd_start(&g_control, &c) == ESP_OK) {
    httpd_uri_t u;
    u = {"/whoisthis", HTTP_GET, handleStatus, nullptr}; httpd_register_uri_handler(g_control, &u);
    u = {"/capture", HTTP_GET, handleCapture, nullptr}; httpd_register_uri_handler(g_control, &u);
    u = {"/events", HTTP_GET, handleEvents, nullptr}; httpd_register_uri_handler(g_control, &u);
    u = {"/present", HTTP_POST, handlePresent, nullptr}; httpd_register_uri_handler(g_control, &u);
    u = {"/wifi", HTTP_POST, handleWifi, nullptr}; httpd_register_uri_handler(g_control, &u);
    u = {"/", HTTP_GET, handleRoot, nullptr}; httpd_register_uri_handler(g_control, &u);
    httpd_register_err_handler(g_control, HTTPD_404_NOT_FOUND, handleNotFound);
  }
  if (g_apMode) return;  // no streaming until the board is on a real network
  httpd_config_t s = HTTPD_DEFAULT_CONFIG();
  s.server_port = WIT_STREAM_PORT;
  s.ctrl_port = 32769;
  s.max_open_sockets = 2;  // one plugin, one browser at most; the sensor is not shareable anyway
  if (httpd_start(&g_stream, &s) == ESP_OK) {
    httpd_uri_t u = {"/stream", HTTP_GET, handleStream, nullptr};
    httpd_register_uri_handler(g_stream, &u);
  }
}

// ---------------------------------------------------------------- Wi-Fi

static bool joinWifi(const String& ssid, const String& pass) {
  if (ssid.isEmpty()) return false;
  Serial.printf("wifi: joining %s\n", ssid.c_str());
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(g_hostname.c_str());
  WiFi.begin(ssid.c_str(), pass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < WIT_WIFI_CONNECT_TIMEOUT_MS) { delay(200); pollLed(); }
  return WiFi.status() == WL_CONNECTED;
}

static void startAccessPoint() {
  g_apMode = true;
  WiFi.mode(WIFI_AP);
  WiFi.softAP(g_hostname.c_str());  // open network; the portal only takes Wi-Fi credentials
  g_dns.start(53, "*", WiFi.softAPIP());
  Serial.printf("wifi: access point %s at %s\n", g_hostname.c_str(), WiFi.softAPIP().toString().c_str());
  g_blinkUntil = UINT32_MAX;  // steady blink = needs provisioning
}

// ---------------------------------------------------------------- setup / loop

void setup() {
  Serial.begin(115200);
  delay(100);
  g_id = "xiao-" + macSuffix();
  g_hostname = "whoisthis-cam-" + macSuffix();

  if (WIT_LED_PIN >= 0) { pinMode(WIT_LED_PIN, OUTPUT); digitalWrite(WIT_LED_PIN, HIGH); }
#if WIT_BUTTON_PIN >= 0
  pinMode(WIT_BUTTON_PIN, INPUT_PULLUP);
#endif
#if WIT_BATTERY_PIN >= 0
  analogSetPinAttenuation(WIT_BATTERY_PIN, ADC_11db);
#endif
#if WIT_OLED
  Wire.begin();
  Wire.beginTransmission(WIT_OLED_ADDR);
  g_oledOk = Wire.endTransmission() == 0 && oled.begin(SSD1306_SWITCHCAPVCC, WIT_OLED_ADDR);
  if (g_oledOk) { oled.setTextColor(SSD1306_WHITE); showText("WhoIsThis\nstarting"); }
#endif

  g_cameraOk = initCamera();
  Serial.printf("camera: %s\n", g_cameraOk ? "ok" : "FAILED");

  g_prefs.begin("wit", true);
  String ssid = g_prefs.getString("ssid", WIT_WIFI_SSID);
  String pass = g_prefs.getString("pass", WIT_WIFI_PASS);
  g_prefs.end();

  if (!joinWifi(ssid, pass)) startAccessPoint();
  else {
    Serial.printf("wifi: %s\n", WiFi.localIP().toString().c_str());
    if (MDNS.begin(g_hostname.c_str())) {
      MDNS.addService("whoisthis", "tcp", WIT_HTTP_PORT);
      MDNS.addServiceTxt("whoisthis", "tcp", "id", g_id);
      MDNS.addServiceTxt("whoisthis", "tcp", "model", WIT_MODEL);
      MDNS.addServiceTxt("whoisthis", "tcp", "caps", capabilitiesTxt());
      MDNS.addServiceTxt("whoisthis", "tcp", "stream_port", String(WIT_STREAM_PORT));
      MDNS.addServiceTxt("whoisthis", "tcp", "fw", WIT_FIRMWARE_VERSION);
      MDNS.addService("http", "tcp", WIT_HTTP_PORT);
    }
    if (g_oledOk) showText(g_hostname + "\n" + WiFi.localIP().toString());
  }
  sampleBattery(true);
  startServers();
}

void loop() {
  if (g_apMode) g_dns.processNextRequest();
  pollButton();
  pollLed();
  sampleBattery(false);
  if (!g_apMode && WiFi.status() != WL_CONNECTED) {
    static uint32_t lastTry = 0;
    if (millis() - lastTry > 10000) { lastTry = millis(); WiFi.reconnect(); }
  }
  delay(5);
}
