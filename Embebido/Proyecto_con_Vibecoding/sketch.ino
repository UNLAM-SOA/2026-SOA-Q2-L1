/*
 ╔══════════════════════════════════════════════════════════════════════════╗
 ║  SIMÓN DICE  ·  "Juegito"  ·  ESP32 firmware  ·  Vibecoding edition      ║
 ╚══════════════════════════════════════════════════════════════════════════╝

  A memory game: the board plays a growing colour sequence, the player repeats
  it on three buttons + one pressure sensor, three lives, shrinking timeouts.
  A companion Android app starts the game and mirrors it live over MQTT; a
  Node-RED flow (Edge-Cloud) archives every message into SQLite.

  ── Architecture (top to bottom) ───────────────────────────────────────────
    1. cfg        Compile-time configuration (pins, timings, network)
    2. logger     Levelled, timestamped, emoji-decorated serial logging
    3. hw         Thin hardware abstraction (LEDs, buzzer, inputs)
    4. fx         Non-blocking LED + buzzer "cue" engine (own task)
    5. net        WiFi + MQTT layer with exponential backoff (own task)
    6. game       Pure game state + rules (owned by the FSM thread only)
    7. input      Button/pressure debouncing task + player-turn task
    8. fsm        The 8-state game state machine (runs in loop())

  ── Concurrency model (why there are no data races) ────────────────────────
    * Every piece of mutable game state lives in `game::g` and is touched ONLY
      by the FSM (Arduino loop task).
    * Other tasks communicate exclusively through FreeRTOS queues:
        g_events   -> FSM          (everything that can change the state)
        g_cues     -> fx task      (LED/buzzer requests, copied by value)
        g_publish  -> net task     (fully formatted MQTT payloads)
        g_touches  -> turn task    (debounced presses)
        g_turnReq  -> turn task    (immutable snapshot of the sequence)
    * The serial log is serialised with a mutex.

  ── MQTT contract (byte-compatible with the original Node-RED flow) ────────
    juegito/<id>/esp32/conexion       "online"/"offline"   retained (LWT)
    juegito/<id>/esp32/nivel          {"nivel":..}         retained
    juegito/<id>/esp32/toque          {"elemento":..}
    juegito/<id>/esp32/timeout        {"paso":..}
    juegito/<id>/esp32/nivel_superado {"nivel":..}
    juegito/<id>/esp32/game_over      {"nivel":..,"secuencia":[..]}
    juegito/<id>/app/conexion         "online"/"offline"   (subscribed)
    juegito/<id>/app/start            "start"              (subscribed)

  Extra, OPTIONAL live telemetry (lives OUTSIDE the prefix Node-RED listens
  to, so the SQLite history is not polluted):
    juegito-live/<id>/esp32/paso      sequence playback + "your turn" signal
*/

#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <freertos/timers.h>
#include <stdarg.h>

// ════════════════════════════════════════════════════════════════════════════
//  1. CONFIGURATION
// ════════════════════════════════════════════════════════════════════════════
namespace cfg {

// ── Pins (identical wiring to the original project's diagram.json) ─────────
constexpr uint8_t PIN_LED_RED     = 26;
constexpr uint8_t PIN_LED_BLUE    = 27;
constexpr uint8_t PIN_LED_GREEN   = 14;
constexpr uint8_t PIN_BTN_RED     = 18;
constexpr uint8_t PIN_BTN_BLUE    = 19;
constexpr uint8_t PIN_BTN_GREEN   = 21;
constexpr uint8_t PIN_DIFFICULTY  = 34;  // potentiometer #1
constexpr uint8_t PIN_PRESSURE    = 35;  // potentiometer #2 acting as pressure sensor
constexpr uint8_t PIN_BUZZER      = 13;  // same pin as the original sketch

// ── Game rules ──────────────────────────────────────────────────────────────
constexpr int      INITIAL_LIVES        = 3;
constexpr int      INITIAL_SEQUENCE_LEN = 3;
constexpr int      MAX_SEQUENCE_LEN     = 50;
constexpr uint32_t TIMEOUT_EASY_MS      = 5000;
constexpr uint32_t TIMEOUT_NORMAL_MS    = 3000;
constexpr uint32_t TIMEOUT_HARD_MS      = 1500;
constexpr int      ADC_LIMIT_EASY       = 1365;  // 0..1364    -> easy
constexpr int      ADC_LIMIT_NORMAL     = 2730;  // 1365..2729 -> normal, rest hard
constexpr int      LEVEL_DECAY_PERCENT  = 90;    // timeout kept after each level
constexpr uint32_t MIN_TIMEOUT_MS       = 600;
constexpr int      REWARD_SECONDS_PER_LEVEL = 5; // seconds of "premio" video per level
constexpr uint32_t REWARD_WINDOW_MS     = 2000;  // pause between levels

// ── Inputs ──────────────────────────────────────────────────────────────────
constexpr int      PRESSURE_THRESHOLD   = 3000;  // ADC value above which = pressed
constexpr uint32_t INPUT_POLL_MS        = 5;
constexpr uint32_t DEBOUNCE_RELEASE_MS  = 30;

// ── Feedback ────────────────────────────────────────────────────────────────
constexpr uint16_t TOUCH_FEEDBACK_MS    = 200;
constexpr uint16_t TONE_ERROR_HZ        = 110;

// ── Network ─────────────────────────────────────────────────────────────────
constexpr char     WIFI_SSID[]          = "Wokwi-GUEST";
constexpr char     WIFI_PASSWORD[]      = "";
constexpr char     DEVICE_ID[]          = "juegito-l01";
constexpr char     MQTT_BROKER[]        = "broker.hivemq.com";
constexpr uint16_t MQTT_PORT            = 1883;
constexpr size_t   MQTT_BUFFER_BYTES    = 1024;  // game_over with 50 colours ~ 500 B
constexpr size_t   PAYLOAD_LEN          = 640;
constexpr size_t   TOPIC_LEN            = 80;
constexpr uint32_t MQTT_BACKOFF_MIN_MS  = 1000;
constexpr uint32_t MQTT_BACKOFF_MAX_MS  = 30000;
constexpr uint32_t WIFI_RETRY_MS        = 10000;

// ── Logging: 0=debug 1=info 2=warn 3=error ─────────────────────────────────
constexpr int      LOG_LEVEL            = 0;

}  // namespace cfg

// ════════════════════════════════════════════════════════════════════════════
//  2. LOGGER
// ════════════════════════════════════════════════════════════════════════════
namespace logger {

enum Level { DEBUG = 0, INFO = 1, WARN = 2, ERROR = 3 };

static SemaphoreHandle_t lock = nullptr;

void init() { lock = xSemaphoreCreateMutex(); }

/** printf-style log line: `[mm:ss.mmm] 💬 INFO  [TAG ] message`. Thread-safe. */
void write(Level level, const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
void write(Level level, const char* tag, const char* fmt, ...) {
  if (level < cfg::LOG_LEVEL) return;
  static const char* const ICONS[] = {"🐛", "💬", "⚠️ ", "🛑"};
  static const char* const NAMES[] = {"DEBUG", "INFO ", "WARN ", "ERROR"};

  char msg[220];
  va_list args;
  va_start(args, fmt);
  vsnprintf(msg, sizeof(msg), fmt, args);
  va_end(args);

  const uint32_t t = millis();
  if (lock) xSemaphoreTake(lock, portMAX_DELAY);
  Serial.printf("[%02u:%02u.%03u] %s %s [%-5s] %s\n",
                (unsigned)(t / 60000), (unsigned)((t / 1000) % 60), (unsigned)(t % 1000),
                ICONS[level], NAMES[level], tag, msg);
  if (lock) xSemaphoreGive(lock);
}

}  // namespace logger

#define LOGD(tag, ...) logger::write(logger::DEBUG, tag, __VA_ARGS__)
#define LOGI(tag, ...) logger::write(logger::INFO,  tag, __VA_ARGS__)
#define LOGW(tag, ...) logger::write(logger::WARN,  tag, __VA_ARGS__)
#define LOGE(tag, ...) logger::write(logger::ERROR, tag, __VA_ARGS__)

// ════════════════════════════════════════════════════════════════════════════
//  3. DOMAIN TYPES + HARDWARE ABSTRACTION
// ════════════════════════════════════════════════════════════════════════════

using ElementId = int8_t;
constexpr ElementId ELEM_NONE     = -1;
constexpr ElementId ELEM_RED      = 0;
constexpr ElementId ELEM_BLUE     = 1;
constexpr ElementId ELEM_GREEN    = 2;
constexpr ElementId ELEM_PRESSURE = 3;
constexpr int       ELEMENT_COUNT = 4;

constexpr uint8_t LED_RED_BIT = 1, LED_BLUE_BIT = 2, LED_GREEN_BIT = 4, LED_ALL = 7;

struct ElementInfo {
  const char* name;    // wire name: the app and Node-RED history depend on it
  uint8_t     ledMask;
  uint16_t    toneHz;
  uint8_t     pin;
  bool        analog;  // true = threshold on ADC, false = active-low button
};

static const ElementInfo ELEMENTS[ELEMENT_COUNT] = {
  {"ROJO",   LED_RED_BIT,   262, cfg::PIN_BTN_RED,   false},  // C4
  {"AZUL",   LED_BLUE_BIT,  330, cfg::PIN_BTN_BLUE,  false},  // E4
  {"VERDE",  LED_GREEN_BIT, 392, cfg::PIN_BTN_GREEN, false},  // G4
  {"SENSOR", LED_ALL,       523, cfg::PIN_PRESSURE,  true},   // C5 (all LEDs)
};

namespace hw {

void init() {
  pinMode(cfg::PIN_LED_RED, OUTPUT);
  pinMode(cfg::PIN_LED_BLUE, OUTPUT);
  pinMode(cfg::PIN_LED_GREEN, OUTPUT);
  pinMode(cfg::PIN_BTN_RED, INPUT_PULLUP);
  pinMode(cfg::PIN_BTN_BLUE, INPUT_PULLUP);
  pinMode(cfg::PIN_BTN_GREEN, INPUT_PULLUP);
  pinMode(cfg::PIN_BUZZER, OUTPUT);
}

void setLeds(uint8_t mask) {
  digitalWrite(cfg::PIN_LED_RED,   (mask & LED_RED_BIT)   ? HIGH : LOW);
  digitalWrite(cfg::PIN_LED_BLUE,  (mask & LED_BLUE_BIT)  ? HIGH : LOW);
  digitalWrite(cfg::PIN_LED_GREEN, (mask & LED_GREEN_BIT) ? HIGH : LOW);
}

void setTone(uint16_t hz) {
  if (hz) tone(cfg::PIN_BUZZER, hz);
  else    noTone(cfg::PIN_BUZZER);
}

bool isPressed(ElementId id) {
  const ElementInfo& e = ELEMENTS[id];
  return e.analog ? analogRead(e.pin) > cfg::PRESSURE_THRESHOLD
                  : digitalRead(e.pin) == LOW;
}

uint32_t difficultyTimeoutMs() {
  const int raw = analogRead(cfg::PIN_DIFFICULTY);
  if (raw < cfg::ADC_LIMIT_EASY)   return cfg::TIMEOUT_EASY_MS;
  if (raw < cfg::ADC_LIMIT_NORMAL) return cfg::TIMEOUT_NORMAL_MS;
  return cfg::TIMEOUT_HARD_MS;
}

const char* difficultyName(uint32_t baseTimeoutMs) {
  if (baseTimeoutMs == cfg::TIMEOUT_EASY_MS)   return "FACIL";
  if (baseTimeoutMs == cfg::TIMEOUT_NORMAL_MS) return "NORMAL";
  return "DIFICIL";
}

}  // namespace hw

// ════════════════════════════════════════════════════════════════════════════
//  SHARED TYPES: events, MQTT topics, queues
// ════════════════════════════════════════════════════════════════════════════

enum State : uint8_t {
  ST_WAITING_APP, ST_IDLE, ST_NEW_LEVEL, ST_PLAYING_SEQUENCE,
  ST_AWAITING_INPUT, ST_EVALUATING_LIVES, ST_REWARD, ST_GAME_OVER
};
// Wire-level state names stay in Spanish to match the original documentation.
static const char* const STATE_NAMES[] = {
  "ESPERANDO_APP", "IDLE", "NUEVO_NIVEL", "REPRODUCIENDO_SECUENCIA",
  "ESPERANDO_INGRESO", "EVALUANDO_VIDAS", "RECOMPENSA", "GAME_OVER"
};

enum EventType : uint8_t {
  EV_APP_ONLINE, EV_START, EV_LEVEL_READY, EV_SEQUENCE_DONE,
  EV_INPUT_OK, EV_INPUT_WRONG, EV_LEVEL_CLEARED, EV_INPUT_TIMEOUT,
  EV_LIVES_LEFT, EV_NO_LIVES, EV_REWARD_ELAPSED
};
static const char* const EVENT_NAMES[] = {
  "APP_ONLINE", "START", "LEVEL_READY", "SEQUENCE_DONE",
  "INPUT_OK", "INPUT_WRONG", "LEVEL_CLEARED", "INPUT_TIMEOUT",
  "LIVES_LEFT", "NO_LIVES", "REWARD_ELAPSED"
};

struct Event {
  EventType type;
  ElementId element;  // pressed element, ELEM_NONE if n/a
  uint8_t   step;     // 1-based position pressed/expected, 0 if n/a
};

enum TopicId : uint8_t {
  TOP_ESP_STATUS, TOP_APP_STATUS, TOP_APP_START, TOP_LEVEL, TOP_TOUCH,
  TOP_TIMEOUT, TOP_LEVEL_CLEARED, TOP_GAME_OVER, TOP_LIVE_STEP, TOPIC_COUNT
};

struct OutMessage {
  TopicId topic;
  bool    retain;
  char    payload[cfg::PAYLOAD_LEN];
};

enum CueType : uint8_t { CUE_STARTUP, CUE_TOUCH, CUE_ERROR, CUE_LEVEL_UP, CUE_GAME_OVER, CUE_SEQUENCE };

struct CueRequest {
  CueType   type;
  ElementId element;                        // CUE_TOUCH
  uint8_t   length;                         // CUE_SEQUENCE
  uint8_t   generation;                     // stamped by fx::play(); stale cues are discarded
  uint16_t  onMs, offMs;                    // CUE_SEQUENCE
  uint8_t   sequence[cfg::MAX_SEQUENCE_LEN];
};

struct TurnRequest {
  uint8_t  length;
  uint32_t timeoutMs;
  uint8_t  sequence[cfg::MAX_SEQUENCE_LEN];
};

static QueueHandle_t g_events  = nullptr;
static QueueHandle_t g_cues    = nullptr;
static QueueHandle_t g_publish = nullptr;
static QueueHandle_t g_touches = nullptr;
static QueueHandle_t g_turnReq = nullptr;
static TaskHandle_t  g_turnTask = nullptr;
static TimerHandle_t g_rewardTimer = nullptr;

static void postEvent(EventType type, ElementId el = ELEM_NONE, uint8_t step = 0) {
  Event ev = {type, el, step};
  if (xQueueSend(g_events, &ev, pdMS_TO_TICKS(50)) != pdTRUE) {
    LOGW("EVENT", "queue full, dropped %s", EVENT_NAMES[type]);
  }
}

// ════════════════════════════════════════════════════════════════════════════
//  4. FX ENGINE: non-blocking LED + buzzer cues
// ════════════════════════════════════════════════════════════════════════════
//  A cue is a list of timed steps {frequency, LED mask, duration}. The engine
//  task advances it by comparing millis() timestamps, it never sleeps inside a
//  cue, so a new request (or an abort) is honoured within ~2 ms. When no cue is
//  active it renders the "ambient" animation (LED chase) if one is selected.
namespace fx {

enum Ambient : uint8_t { AMBIENT_OFF, AMBIENT_WAITING_APP, AMBIENT_READY };

struct Step { uint16_t hz; uint8_t mask; uint16_t ms; };
constexpr int MAX_STEPS = 2 * cfg::MAX_SEQUENCE_LEN + 8;

static volatile Ambient ambient = AMBIENT_OFF;
static volatile uint8_t generation = 0;  // bumped by abortAll(): older cues become stale

static void publishLiveStep(const char* element, int step, int of);  // defined in net section

/** Appends a step; returns the new count (silently clamps at MAX_STEPS). */
static int add(Step* s, int n, uint16_t hz, uint8_t mask, uint16_t ms) {
  if (n < MAX_STEPS) s[n] = {hz, mask, ms};
  return n < MAX_STEPS ? n + 1 : n;
}

static int expand(const CueRequest& r, Step* s) {
  int n = 0;
  switch (r.type) {
    case CUE_STARTUP: {  // rising C-major arpeggio, LEDs follow the notes
      const uint16_t notes[] = {523, 659, 784, 1047};
      const uint8_t  masks[] = {LED_RED_BIT, LED_BLUE_BIT, LED_GREEN_BIT, LED_ALL};
      for (int i = 0; i < 4; i++) {
        n = add(s, n, notes[i], masks[i], 110);
        n = add(s, n, 0, 0, 25);
      }
      n = add(s, n, 0, 0, 100);
      break;
    }
    case CUE_TOUCH:
      n = add(s, n, ELEMENTS[r.element].toneHz, ELEMENTS[r.element].ledMask, cfg::TOUCH_FEEDBACK_MS);
      break;
    case CUE_ERROR:  // low buzz, all LEDs blinking twice (600 ms total)
      for (int i = 0; i < 2; i++) {
        n = add(s, n, cfg::TONE_ERROR_HZ, LED_ALL, 150);
        n = add(s, n, cfg::TONE_ERROR_HZ, 0, 150);
      }
      break;
    case CUE_LEVEL_UP: {  // victory fanfare (fits well inside the 2 s reward window)
      const uint16_t notes[] = {523, 659, 784, 1047};
      for (int i = 0; i < 4; i++) {
        n = add(s, n, notes[i], (i % 2) ? LED_ALL : 0, 90);
        n = add(s, n, notes[i], LED_ALL, 40);
      }
      n = add(s, n, 1047, LED_ALL, 320);
      n = add(s, n, 0, 0, 20);
      break;
    }
    case CUE_GAME_OVER: {  // descending "wah-wah-wah-waaah"
      const uint16_t notes[] = {392, 370, 349, 330, 262, 196};
      const uint16_t lens[]  = {200, 200, 200, 200, 300, 600};
      for (int i = 0; i < 6; i++) {
        n = add(s, n, notes[i], (i % 2) ? 0 : LED_ALL, lens[i]);
      }
      n = add(s, n, cfg::TONE_ERROR_HZ, LED_ALL, 400);
      break;
    }
    case CUE_SEQUENCE:  // 50 ms of darkness, then [on, off] per element
      n = add(s, n, 0, 0, 50);
      for (int i = 0; i < r.length; i++) {
        const ElementInfo& e = ELEMENTS[r.sequence[i]];
        n = add(s, n, e.toneHz, e.ledMask, r.onMs);
        n = add(s, n, 0, 0, r.offMs);
      }
      break;
  }
  return n;
}

static void apply(const Step& st) {
  hw::setLeds(st.mask);
  hw::setTone(st.hz);
}

static int8_t ambientFrame = -2;

static void renderAmbient(uint32_t now) {
  int8_t frame = -1;
  uint8_t mask = 0;
  if (ambient == AMBIENT_WAITING_APP) {          // slow single-LED sweep: R B G B
    static const uint8_t pat[] = {LED_RED_BIT, LED_BLUE_BIT, LED_GREEN_BIT, LED_BLUE_BIT};
    frame = (now / 320) % 4; mask = pat[frame];
  } else if (ambient == AMBIENT_READY) {         // faster sweep with a flash at the end
    static const uint8_t pat[] = {LED_RED_BIT, LED_BLUE_BIT, LED_GREEN_BIT, LED_BLUE_BIT, 0, LED_ALL, 0, 0};
    frame = (now / 140) % 8; mask = pat[frame];
  } else {
    frame = -1; mask = 0;
  }
  if (frame != ambientFrame) {
    ambientFrame = frame;
    hw::setLeds(mask);
  }
}

static void task(void*) {
  static Step steps[MAX_STEPS];
  CueRequest req;
  int count = 0, idx = 0;
  bool active = false;
  uint32_t stepStart = 0;

  for (;;) {
    const uint32_t now = millis();

    // Race-free abort: cues carry the generation they were queued in, so a cue
    // queued AFTER abortAll() can never be killed by it.
    if (active && req.generation != generation) {
      active = false; hw::setLeds(0); hw::setTone(0);
    }

    if (!active) {
      if (xQueueReceive(g_cues, &req, 0) == pdTRUE) {
        if (req.generation != generation) continue;  // stale, drop it
        count = expand(req, steps);
        idx = 0; active = count > 0; stepStart = now;
        if (active) {
          ambientFrame = -2;  // force the ambient layer to repaint after this cue
          apply(steps[0]);
        }
      } else {
        renderAmbient(now);
      }
    } else if (now - stepStart >= steps[idx].ms) {
      idx++;
      stepStart = now;
      if (idx >= count) {
        active = false;
        hw::setLeds(0); hw::setTone(0);
        if (req.type == CUE_SEQUENCE) postEvent(EV_SEQUENCE_DONE);
      } else {
        apply(steps[idx]);
        // Odd/even bookkeeping for the live telemetry: element i lights at step 1+2i
        if (req.type == CUE_SEQUENCE && idx >= 1 && ((idx - 1) % 2 == 0)) {
          const int i = (idx - 1) / 2;
          publishLiveStep(ELEMENTS[req.sequence[i]].name, i + 1, req.length);
        }
      }
    }
    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

static void play(CueRequest r) {
  r.generation = generation;
  if (xQueueSend(g_cues, &r, 0) != pdTRUE) LOGW("FX", "cue queue full, dropped cue %d", r.type);
}
static void playSimple(CueType t, ElementId el = ELEM_NONE) {
  CueRequest r = {};
  r.type = t; r.element = el;
  play(r);
}
static void abortAll() { generation = generation + 1; xQueueReset(g_cues); }
static void setAmbient(Ambient a) { ambient = a; }

}  // namespace fx

// ════════════════════════════════════════════════════════════════════════════
//  5. NETWORK: WiFi + MQTT with reconnect/backoff
// ════════════════════════════════════════════════════════════════════════════
namespace net {

static const char* const SUFFIX[TOPIC_COUNT] = {
  "esp32/conexion", "app/conexion", "app/start", "esp32/nivel", "esp32/toque",
  "esp32/timeout", "esp32/nivel_superado", "esp32/game_over", "esp32/paso"
};

static char topics[TOPIC_COUNT][cfg::TOPIC_LEN];
static char appWildcard[cfg::TOPIC_LEN];
static WiFiClient wifiClient;
static PubSubClient mqtt(wifiClient);

static void buildTopics() {
  for (int i = 0; i < TOPIC_COUNT; i++) {
    // The live-telemetry topic deliberately uses a DIFFERENT root so Node-RED's
    // `juegito/<id>/#` subscription does not store it.
    const char* root = (i == TOP_LIVE_STEP) ? "juegito-live" : "juegito";
    snprintf(topics[i], cfg::TOPIC_LEN, "%s/%s/%s", root, cfg::DEVICE_ID, SUFFIX[i]);
  }
  snprintf(appWildcard, cfg::TOPIC_LEN, "juegito/%s/app/#", cfg::DEVICE_ID);
}

/** Queue a message for the network task (never blocks the caller). */
static void publish(TopicId topic, const char* payload, bool retain = false) {
  OutMessage m;
  m.topic = topic;
  m.retain = retain;
  strlcpy(m.payload, payload, sizeof(m.payload));
  if (xQueueSend(g_publish, &m, 0) != pdTRUE) {
    LOGW("MQTT", "publish queue full, dropped %s", SUFFIX[topic]);
  }
}

static void onMessage(char* topic, byte* payload, unsigned int length) {
  if (strcmp(topic, topics[TOP_APP_START]) == 0) {
    LOGI("MQTT", "📲 app/start received");
    postEvent(EV_START);
  } else if (strcmp(topic, topics[TOP_APP_STATUS]) == 0) {
    const bool online = (length == 6 && memcmp(payload, "online", 6) == 0);
    LOGI("MQTT", "📲 app is %s", online ? "ONLINE" : "offline");
    if (online) postEvent(EV_APP_ONLINE);
  }
}

static bool connectBroker() {
  char clientId[40];
  // Distinct from the original sketch's client id so both can coexist briefly
  // without kicking each other off the broker.
  snprintf(clientId, sizeof(clientId), "juegito-esp32-vibe-%s", cfg::DEVICE_ID);
  if (!mqtt.connect(clientId, nullptr, nullptr, topics[TOP_ESP_STATUS], 0, true, "offline")) {
    LOGW("MQTT", "connect failed (rc=%d)", mqtt.state());
    return false;
  }
  mqtt.publish(topics[TOP_ESP_STATUS], "online", true);
  mqtt.subscribe(appWildcard, 1);
  LOGI("MQTT", "🟢 connected to %s, subscribed to %s", cfg::MQTT_BROKER, appWildcard);
  return true;
}

static void task(void*) {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(cfg::WIFI_SSID, cfg::WIFI_PASSWORD);
  mqtt.setServer(cfg::MQTT_BROKER, cfg::MQTT_PORT);
  mqtt.setBufferSize(cfg::MQTT_BUFFER_BYTES);
  mqtt.setSocketTimeout(5);
  mqtt.setCallback(onMessage);

  uint32_t backoff = cfg::MQTT_BACKOFF_MIN_MS;
  uint32_t lastWifiKick = millis();
  bool wifiWasUp = false;
  OutMessage m;

  for (;;) {
    // ── WiFi supervision ──
    if (WiFi.status() != WL_CONNECTED) {
      if (wifiWasUp) { LOGW("WIFI", "🔌 link lost, reconnecting..."); wifiWasUp = false; }
      if (millis() - lastWifiKick > cfg::WIFI_RETRY_MS) {
        lastWifiKick = millis();
        LOGW("WIFI", "still offline, kicking WiFi.reconnect()");
        WiFi.reconnect();
      }
      vTaskDelay(pdMS_TO_TICKS(250));
      continue;
    }
    if (!wifiWasUp) {
      wifiWasUp = true;
      LOGI("WIFI", "📶 connected, IP %s", WiFi.localIP().toString().c_str());
    }

    // ── MQTT supervision with exponential backoff ──
    if (!mqtt.connected()) {
      if (connectBroker()) {
        backoff = cfg::MQTT_BACKOFF_MIN_MS;
      } else {
        LOGW("MQTT", "retry in %u ms", (unsigned)backoff);
        vTaskDelay(pdMS_TO_TICKS(backoff));
        backoff = (backoff * 2 > cfg::MQTT_BACKOFF_MAX_MS) ? cfg::MQTT_BACKOFF_MAX_MS : backoff * 2;
      }
      continue;
    }

    mqtt.loop();
    if (xQueueReceive(g_publish, &m, pdMS_TO_TICKS(10)) == pdTRUE) {
      if (mqtt.publish(topics[m.topic], m.payload, m.retain)) {
        if (m.topic != TOP_LIVE_STEP) LOGD("MQTT", "⬆️  %s %s", SUFFIX[m.topic], m.payload);
      } else {
        LOGW("MQTT", "publish to %s failed", SUFFIX[m.topic]);
      }
    }
  }
}

}  // namespace net

// Live telemetry hook used by the fx engine (see section 4).
static void fx::publishLiveStep(const char* element, int step, int of) {
  char p[96];
  snprintf(p, sizeof(p), "{\"fase\":\"secuencia\",\"elemento\":\"%s\",\"paso\":%d,\"de\":%d}", element, step, of);
  net::publish(TOP_LIVE_STEP, p);
}

// ════════════════════════════════════════════════════════════════════════════
//  6. GAME STATE + RULES  (touched ONLY from the FSM thread)
// ════════════════════════════════════════════════════════════════════════════
namespace game {

struct Game {
  int         lives;
  int         level;
  int         rewardSeconds;
  int         length;
  uint8_t     sequence[cfg::MAX_SEQUENCE_LEN];
  uint32_t    timeoutMs;       // current per-touch timeout (shrinks every level)
  const char* difficulty;
};
static Game g;

static void addColor() {
  if (g.length < cfg::MAX_SEQUENCE_LEN) {
    g.sequence[g.length++] = (uint8_t)random(0, ELEMENT_COUNT);
  } else {
    LOGW("GAME", "maximum sequence length reached");
  }
}

static void shrinkTimeout() {
  g.timeoutMs = g.timeoutMs * cfg::LEVEL_DECAY_PERCENT / 100;
  if (g.timeoutMs < cfg::MIN_TIMEOUT_MS) g.timeoutMs = cfg::MIN_TIMEOUT_MS;
}

// ── Payload builders: formats are EXACTLY what Node-RED / the app parse ──
static void publishLevel() {
  char p[cfg::PAYLOAD_LEN];
  snprintf(p, sizeof(p),
           "{\"nivel\":%d,\"largo_secuencia\":%d,\"largo_inicial\":%d,"
           "\"dificultad\":\"%s\",\"timeout_ms\":%lu,\"vidas\":%d}",
           g.level, g.length, cfg::INITIAL_SEQUENCE_LEN, g.difficulty,
           (unsigned long)g.timeoutMs, g.lives);
  net::publish(TOP_LEVEL, p, true);  // retained, like the original
}

static void publishTouch(ElementId el, bool correct, int step) {
  char p[cfg::PAYLOAD_LEN];
  snprintf(p, sizeof(p), "{\"elemento\":\"%s\",\"correcto\":%s,\"paso\":%d,\"de\":%d,\"vidas\":%d}",
           ELEMENTS[el].name, correct ? "true" : "false", step, g.length, g.lives);
  net::publish(TOP_TOUCH, p);
}

static void publishTimeout(int step) {
  char p[cfg::PAYLOAD_LEN];
  snprintf(p, sizeof(p), "{\"paso\":%d,\"de\":%d,\"vidas\":%d}", step, g.length, g.lives);
  net::publish(TOP_TIMEOUT, p);
}

static void publishLevelCleared() {
  char p[cfg::PAYLOAD_LEN];
  snprintf(p, sizeof(p), "{\"nivel\":%d,\"vidas\":%d,\"segundos_video\":%d}",
           g.level, g.lives, g.rewardSeconds);
  net::publish(TOP_LEVEL_CLEARED, p);
}

static void publishGameOver() {
  char p[cfg::PAYLOAD_LEN];
  int n = snprintf(p, sizeof(p), "{\"nivel\":%d,\"segundos_video\":%d,\"secuencia\":[",
                   g.level, g.rewardSeconds);
  for (int i = 0; i < g.length && n < (int)sizeof(p); i++) {
    n += snprintf(p + n, sizeof(p) - n, "%s\"%s\"", i ? "," : "", ELEMENTS[g.sequence[i]].name);
  }
  if (n < (int)sizeof(p)) snprintf(p + n, sizeof(p) - n, "]}");
  net::publish(TOP_GAME_OVER, p);
}

static void announceLevel() {
  LOGI("GAME", "🎯 Level %d: %d colours, %lu ms per touch [%s]", g.level, g.length,
       (unsigned long)g.timeoutMs, g.difficulty);
  publishLevel();
  postEvent(EV_LEVEL_READY);
}

static void begin() {
  g.lives = cfg::INITIAL_LIVES;
  g.level = 1;
  g.rewardSeconds = 0;
  g.length = 0;
  g.timeoutMs = hw::difficultyTimeoutMs();
  g.difficulty = hw::difficultyName(g.timeoutMs);
  for (int i = 0; i < cfg::INITIAL_SEQUENCE_LEN; i++) addColor();
  LOGI("GAME", "🚀 New game! difficulty=%s (read from potentiometer)", g.difficulty);
  announceLevel();
}

static void nextLevel() {
  g.level++;
  addColor();
  shrinkTimeout();
  announceLevel();
}

static void loseLife() {
  g.lives--;
  LOGW("GAME", "💔 Life lost, %d remaining", g.lives);
  postEvent(g.lives > 0 ? EV_LIVES_LEFT : EV_NO_LIVES);
}

static void playSequence() {
  CueRequest r = {};
  r.type = CUE_SEQUENCE;
  r.length = g.length;
  r.onMs = g.timeoutMs / 4;
  r.offMs = g.timeoutMs / 5;
  memcpy(r.sequence, g.sequence, g.length);
  LOGI("GAME", "👀 Playing sequence of %d", g.length);
  fx::play(r);
}

static void startPlayerTurn() {
  TurnRequest t;
  t.length = g.length;
  t.timeoutMs = g.timeoutMs;
  memcpy(t.sequence, g.sequence, g.length);
  xQueueOverwrite(g_turnReq, &t);       // immutable snapshot, no shared state
  xTaskNotifyGive(g_turnTask);
  LOGI("GAME", "👆 Your turn: repeat the sequence (%lu ms per touch)", (unsigned long)g.timeoutMs);

  char p[64];
  snprintf(p, sizeof(p), "{\"fase\":\"turno\",\"timeout_ms\":%lu}", (unsigned long)g.timeoutMs);
  net::publish(TOP_LIVE_STEP, p);
}

}  // namespace game

// ════════════════════════════════════════════════════════════════════════════
//  7. INPUT TASKS
// ════════════════════════════════════════════════════════════════════════════
namespace input {

/** Polls the 3 buttons + pressure sensor, emitting one press edge per touch. */
static void scanTask(void*) {
  bool pressed[ELEMENT_COUNT] = {};
  uint32_t lastSeen[ELEMENT_COUNT] = {};

  for (;;) {
    const uint32_t now = millis();
    for (ElementId id = 0; id < ELEMENT_COUNT; id++) {
      if (hw::isPressed(id)) {
        lastSeen[id] = now;
        if (!pressed[id]) {
          pressed[id] = true;
          LOGD("INPUT", "👉 touch %s", ELEMENTS[id].name);
          xQueueSend(g_touches, &id, 0);
        }
      } else if (pressed[id] && now - lastSeen[id] >= cfg::DEBOUNCE_RELEASE_MS) {
        pressed[id] = false;
      }
    }
    vTaskDelay(pdMS_TO_TICKS(cfg::INPUT_POLL_MS));
  }
}

/** Validates the player's answer step by step against an immutable snapshot. */
static void turnTask(void*) {
  TurnRequest req;
  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    if (xQueueReceive(g_turnReq, &req, 0) != pdTRUE) continue;
    xQueueReset(g_touches);

    uint8_t step = 0;
    for (bool done = false; !done;) {
      ElementId pressed = ELEM_NONE;
      Event ev = {EV_INPUT_OK, ELEM_NONE, (uint8_t)(step + 1)};

      if (xQueueReceive(g_touches, &pressed, pdMS_TO_TICKS(req.timeoutMs)) != pdTRUE) {
        ev.type = EV_INPUT_TIMEOUT;
        done = true;
      } else {
        ev.element = pressed;
        if (pressed != (ElementId)req.sequence[step]) { ev.type = EV_INPUT_WRONG; done = true; }
        else if (step + 1 == req.length)              { ev.type = EV_LEVEL_CLEARED; done = true; }
        else                                          { ev.type = EV_INPUT_OK; step++; }
      }
      xQueueSend(g_events, &ev, portMAX_DELAY);
    }
  }
}

}  // namespace input

// ════════════════════════════════════════════════════════════════════════════
//  8. FINITE STATE MACHINE
// ════════════════════════════════════════════════════════════════════════════
namespace fsm {

static State state = ST_WAITING_APP;

static void go(State next, EventType cause) {
  LOGI("FSM", "🔀 %s ──[%s]──▶ %s", STATE_NAMES[state], EVENT_NAMES[cause], STATE_NAMES[next]);
  state = next;
}

static void enterReady() {
  fx::setAmbient(fx::AMBIENT_READY);
  LOGI("GAME", "✅ App linked. Waiting for START (app button, or 's' on serial)...");
}

static void startGame(EventType cause) {
  fx::setAmbient(fx::AMBIENT_OFF);
  fx::abortAll();                         // cut any melody still playing
  game::begin();
  go(ST_NEW_LEVEL, cause);
}

static void dispatch(const Event& ev) {
  using namespace game;
  switch (state) {

    case ST_WAITING_APP:
      if (ev.type == EV_APP_ONLINE) { enterReady(); go(ST_IDLE, ev.type); return; }
      break;

    case ST_IDLE:
      if (ev.type == EV_START) { startGame(ev.type); return; }
      break;

    case ST_NEW_LEVEL:
      if (ev.type == EV_LEVEL_READY) { playSequence(); go(ST_PLAYING_SEQUENCE, ev.type); return; }
      break;

    case ST_PLAYING_SEQUENCE:
      if (ev.type == EV_SEQUENCE_DONE) { startPlayerTurn(); go(ST_AWAITING_INPUT, ev.type); return; }
      break;

    case ST_AWAITING_INPUT:
      switch (ev.type) {
        case EV_INPUT_OK:
          fx::playSimple(CUE_TOUCH, ev.element);
          publishTouch(ev.element, true, ev.step);
          LOGI("GAME", "✔️  %s correct (%d/%d)", ELEMENTS[ev.element].name, ev.step, g.length);
          return;  // stays in AWAITING_INPUT
        case EV_LEVEL_CLEARED:
          fx::playSimple(CUE_TOUCH, ev.element);
          publishTouch(ev.element, true, ev.step);
          g.rewardSeconds += cfg::REWARD_SECONDS_PER_LEVEL;
          publishLevelCleared();
          LOGI("GAME", "🏆 Level %d cleared! reward video: %d s", g.level, g.rewardSeconds);
          fx::playSimple(CUE_LEVEL_UP);
          xTimerStart(g_rewardTimer, 0);
          go(ST_REWARD, ev.type);
          return;
        case EV_INPUT_WRONG:
          fx::playSimple(CUE_TOUCH, ev.element);
          fx::playSimple(CUE_ERROR);
          loseLife();
          publishTouch(ev.element, false, ev.step);
          LOGW("GAME", "❌ wrong: pressed %s at step %d", ELEMENTS[ev.element].name, ev.step);
          go(ST_EVALUATING_LIVES, ev.type);
          return;
        case EV_INPUT_TIMEOUT:
          fx::playSimple(CUE_ERROR);
          loseLife();
          publishTimeout(ev.step);
          LOGW("GAME", "⏰ timeout at step %d", ev.step);
          go(ST_EVALUATING_LIVES, ev.type);
          return;
        default: break;
      }
      break;

    case ST_EVALUATING_LIVES:
      if (ev.type == EV_LIVES_LEFT) { playSequence(); go(ST_PLAYING_SEQUENCE, ev.type); return; }
      if (ev.type == EV_NO_LIVES) {
        fx::playSimple(CUE_GAME_OVER);
        publishGameOver();
        fx::setAmbient(fx::AMBIENT_READY);
        LOGI("GAME", "☠️  GAME OVER at level %d. Press START in the app (or 's') to play again.", g.level);
        go(ST_GAME_OVER, ev.type);
        return;
      }
      break;

    case ST_REWARD:
      if (ev.type == EV_REWARD_ELAPSED) { nextLevel(); go(ST_NEW_LEVEL, ev.type); return; }
      break;

    case ST_GAME_OVER:
      if (ev.type == EV_START) { startGame(ev.type); return; }
      break;
  }
  LOGD("FSM", "ignored %s in %s", EVENT_NAMES[ev.type], STATE_NAMES[state]);
}

/** Serial debug shortcuts, same as the original: 'c' = app connected, 's' = start. */
static void pollSerial() {
  while (Serial.available() > 0) {
    const char c = Serial.read();
    if (c == 'c') { LOGI("SERIAL", "⌨️  simulated app connection"); postEvent(EV_APP_ONLINE); }
    if (c == 's') { LOGI("SERIAL", "⌨️  simulated START");          postEvent(EV_START); }
  }
}

}  // namespace fsm

// ════════════════════════════════════════════════════════════════════════════
//  ARDUINO ENTRY POINTS
// ════════════════════════════════════════════════════════════════════════════

static void rewardTimerCallback(TimerHandle_t) { postEvent(EV_REWARD_ELAPSED); }

static void printBanner() {
  Serial.println();
  Serial.println(R"BANNER(
   ____  ___ __  __  ___  _   _
  / ___||_ _|  \/  |/ _ \| \ | |
  \___ \ | || |\/| | | | |  \| |
   ___) || || |  | | |_| | |\  |
  |____/|___|_|  |_|\___/|_| \_|
   ____ ___ ____ _____
  |  _ \_ _/ ___| ____|
  | | | | | |   |  _|
  | |_| | | |___| |___
  |____/___\____|_____|

  🔴 🔵 🟢   J U E G I T O  ·  ESP32  🎮
  ═════ vibecoding edition · FreeRTOS · MQTT ═════
)BANNER");
  Serial.printf("  chip: %s rev %d | cores: %d | cpu: %u MHz | free heap: %u B\n\n",
                ESP.getChipModel(), (int)ESP.getChipRevision(), (int)ESP.getChipCores(),
                (unsigned)ESP.getCpuFreqMHz(), (unsigned)ESP.getFreeHeap());
}

void setup() {
  Serial.begin(115200);
  logger::init();
  printBanner();

  hw::init();
  randomSeed(esp_random());

  g_events  = xQueueCreate(16, sizeof(Event));
  g_cues    = xQueueCreate(12, sizeof(CueRequest));
  g_publish = xQueueCreate(16, sizeof(OutMessage));
  g_touches = xQueueCreate(10, sizeof(ElementId));
  g_turnReq = xQueueCreate(1,  sizeof(TurnRequest));
  g_rewardTimer = xTimerCreate("reward", pdMS_TO_TICKS(cfg::REWARD_WINDOW_MS), pdFALSE, nullptr, rewardTimerCallback);
  net::buildTopics();

  LOGI("BOOT", "🔌 pins: LED R/B/G=%d/%d/%d  BTN R/B/G=%d/%d/%d  POT=%d  SENSOR=%d  BUZZER=%d",
       cfg::PIN_LED_RED, cfg::PIN_LED_BLUE, cfg::PIN_LED_GREEN,
       cfg::PIN_BTN_RED, cfg::PIN_BTN_BLUE, cfg::PIN_BTN_GREEN,
       cfg::PIN_DIFFICULTY, cfg::PIN_PRESSURE, cfg::PIN_BUZZER);
  LOGI("BOOT", "📡 topics under juegito/%s/  (live telemetry: juegito-live/%s/)", cfg::DEVICE_ID, cfg::DEVICE_ID);

  xTaskCreate(fx::task,         "fx",    4096, nullptr, 3, nullptr);
  xTaskCreate(input::scanTask,  "scan",  4096, nullptr, 3, nullptr);
  xTaskCreate(input::turnTask,  "turn",  4096, nullptr, 2, &g_turnTask);
  xTaskCreate(net::task,        "net",   8192, nullptr, 2, nullptr);

  fx::playSimple(CUE_STARTUP);
  fx::setAmbient(fx::AMBIENT_WAITING_APP);
  LOGI("BOOT", "✨ ready, state=%s. Waiting for the app to come online...", STATE_NAMES[fsm::state]);
}

void loop() {
  Event ev;
  if (xQueueReceive(g_events, &ev, pdMS_TO_TICKS(20)) == pdTRUE) {
    fsm::dispatch(ev);
  }
  fsm::pollSerial();
}
