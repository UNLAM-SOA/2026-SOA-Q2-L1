#include <Arduino.h>
#include <freertos/timers.h>
#include <freertos/semphr.h>
#include <esp_system.h>
#include <WiFi.h>
#include <PubSubClient.h>

// ---------------- Pines ----------------

#define PIN_LED_ROJO        26
#define PIN_LED_AZUL        27
#define PIN_LED_VERDE       14


#define PIN_BTN_ROJO        18
#define PIN_BTN_AZUL        19
#define PIN_BTN_VERDE       21


#define PIN_POTENCIOMETRO   34
#define PIN_SENSOR_PRESION  35


#define PIN_BUZZER          13

// ---------------- Estados ----------------
enum estado_t {
  ESTADO_ESPERANDO_APP,
  ESTADO_IDLE,
  ESTADO_NUEVO_NIVEL,
  ESTADO_REPRODUCIENDO_SECUENCIA,
  ESTADO_ESPERANDO_INGRESO,
  ESTADO_EVALUANDO_VIDAS,
  ESTADO_RECOMPENSA,
  ESTADO_GAME_OVER
};

// ---------------- Eventos ----------------
enum evento_t {
  EV_CONTINUE, 
  EV_APP_CONECTADA,
  EV_BOTON_START,
  EV_CALCULO_LISTO,
  EV_TIMER_TICK,
  EV_SECUENCIA_COMPLETA,
  EV_INGRESO_CORRECTO,
  EV_INGRESO_INCORRECTO,
  EV_NIVEL_SUPERADO,
  EV_TIMEOUT,
  EV_VIDAS_MAYOR_CERO,
  EV_VIDAS_CERO
};

const char* nombres_estados[] = {
  "ESPERANDO_APP", "IDLE", "NUEVO_NIVEL", "REPRODUCIENDO_SECUENCIA",
  "ESPERANDO_INGRESO", "EVALUANDO_VIDAS", "RECOMPENSA", "GAME_OVER"
};

const char* nombres_eventos[] = {
  "EV_CONTINUE", "EV_APP_CONECTADA", "EV_BOTON_START",
  "EV_CALCULO_LISTO", "EV_TIMER_TICK", "EV_SECUENCIA_COMPLETA",
  "EV_INGRESO_CORRECTO", "EV_INGRESO_INCORRECTO", "EV_NIVEL_SUPERADO",
  "EV_TIMEOUT", "EV_VIDAS_MAYOR_CERO", "EV_VIDAS_CERO"
};

// ---------------- Variables de la maquina de estados ----------------
estado_t estado_actual;
evento_t evento_actual;

// ---------------- Datos del juego ----------------
#define ELEM_ROJO       0
#define ELEM_AZUL       1
#define ELEM_VERDE      2
#define ELEM_SENSOR     3
#define CANT_ELEMENTOS  4

// Tono del buzzer para cada elemento (Hz)
#define TONO_ROJO       262   // Do
#define TONO_AZUL       330   // Mi
#define TONO_VERDE      392   // Sol
#define TONO_SENSOR     523   // Do (mas agudo)
#define TONO_ERROR      110   // La (grave)

//Configuracion Inicial
#define VIDAS_INICIALES  3
#define MAX_SECUENCIA    50
#define LARGO_INICIAL    3

//Dificultad
#define TIMEOUT_FACIL    5000   // ms
#define TIMEOUT_NORMAL   3000
#define TIMEOUT_DIFICIL  1500

#define LIMITE_FACIL     1365   // 0    - 1364 -> facil
#define LIMITE_NORMAL    2730   // 1365 - 2729 -> normal
                                // 2730 - 4095 -> dificil

#define FACTOR_NIVEL     90     // % del tiempo que queda al pasar de nivel
#define TIMEOUT_MINIMO   600    // ms: por mas que suba el nivel, no baja de aca

//Video de premio
#define SEGUNDOS_POR_NIVEL  5   // Segundos de video que suma cada nivel superado

unsigned long timeout_ingreso;
const char*   dificultad_actual = "";

int vidas;
int nivel;
int segundos_video;   // Premio acumulado en la partida; la app lo muestra al perder
int secuencia[MAX_SECUENCIA];
int largo_secuencia;

// ---------------- FreeRTOS ----------------
#define CANT_ELEMENTOS_COLA   10
#define PERIODO_BOTONES_MS    5      // Cada cuanto cada tarea_boton lee su boton
#define ANTIRREBOTE_MS        30     // Tiempo suelto para aceptar el proximo toque
#define DURACION_FEEDBACK_MS  200    // LED + tono al tocar un boton
#define UMBRAL_PRESION        3000   // Sensor (pot2) por encima de esto = presionado
#define RECOMPENSA_MS         2000   // Lo que dura la recompensa al pasar de nivel

QueueHandle_t cola_botones;
QueueHandle_t cola_eventos;
TaskHandle_t  handle_jugar;
TimerHandle_t timer_descanso;

SemaphoreHandle_t mutex_secuencia;


struct mensaje_t {
  evento_t evento;
  int      elemento;
  int      paso;      // Posicion tocada o esperada, empezando en 1 (0 si no aplica)
};

#define SIN_ELEMENTO  -1

int elemento_ingresado = SIN_ELEMENTO;
int paso_ingresado     = 0;

enum sonido_t {
  SONIDO_TOQUE,
  SONIDO_ERROR,
  SONIDO_RECOMPENSA,
  SONIDO_GAME_OVER,
  SONIDO_SECUENCIA
};

struct pedido_sonido_t {
  sonido_t tipo;
  int      elemento;
};

QueueHandle_t cola_sonidos;

struct entrada_t {
  int         elemento;
  int         pin;
  const char* nombre;
};

entrada_t entradas[CANT_ELEMENTOS] = {
  { ELEM_ROJO,   PIN_BTN_ROJO,       "ROJO"   },
  { ELEM_AZUL,   PIN_BTN_AZUL,       "AZUL"   },
  { ELEM_VERDE,  PIN_BTN_VERDE,      "VERDE"  },
  { ELEM_SENSOR, PIN_SENSOR_PRESION, "SENSOR" },
};

// ============================================================
//  MQTT
// ============================================================

#define WIFI_SSID       "Wokwi-GUEST"
#define WIFI_PASSWORD   ""

#define ID_DISPOSITIVO  "juegito-l01"

#define MQTT_BROKER     "broker.hivemq.com"
#define MQTT_PUERTO     1883
#define MQTT_BUFFER     1024   // game_over con 50 colores ocupa ~500 bytes
#define LARGO_PAYLOAD   640
#define LARGO_TOPICO    64

enum topico_t {
  TOP_ESP32_CONEXION,
  TOP_APP_CONEXION,
  TOP_APP_START,
  TOP_NIVEL,
  TOP_TOQUE,
  TOP_TIMEOUT,
  TOP_NIVEL_SUPERADO,
  TOP_GAME_OVER,
  CANT_TOPICOS
};

const char* sufijos_topicos[CANT_TOPICOS] = {
  "esp32/conexion", 
  "app/conexion", 
  "app/start", 
  "esp32/nivel",
  "esp32/toque", 
  "esp32/timeout", 
  "esp32/nivel_superado", 
  "esp32/game_over"
};

char id_dispositivo[16];
char topicos[CANT_TOPICOS][LARGO_TOPICO];
char topico_app_todos[LARGO_TOPICO];

struct mensaje_mqtt_t {
  topico_t topico;
  char     payload[LARGO_PAYLOAD];
};

QueueHandle_t cola_mqtt;
WiFiClient    cliente_wifi;
PubSubClient  cliente_mqtt(cliente_wifi);


void enviar_mensaje(evento_t ev, int elemento, int paso) {
  mensaje_t msg = { ev, elemento, paso };
  xQueueSend(cola_eventos, &msg, portMAX_DELAY);
}

void generar_evento(evento_t ev) {
  mensaje_t msg = { ev, SIN_ELEMENTO, 0 };
  xQueueSend(cola_eventos, &msg, 0);
}

void pedir_sonido(sonido_t tipo, int elemento = SIN_ELEMENTO) {
  pedido_sonido_t pedido = { tipo, elemento };
  xQueueSend(cola_sonidos, &pedido, 0);
}

void armar_topicos() {
#ifdef ID_DISPOSITIVO
  strlcpy(id_dispositivo, ID_DISPOSITIVO, sizeof(id_dispositivo));
#else
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  snprintf(id_dispositivo, sizeof(id_dispositivo), "%02x%02x%02x", mac[3], mac[4], mac[5]);
#endif

  for (int i = 0; i < CANT_TOPICOS; i++) {
    snprintf(topicos[i], LARGO_TOPICO, "juegito/%s/%s", id_dispositivo, sufijos_topicos[i]);
  }
  snprintf(topico_app_todos, LARGO_TOPICO, "juegito/%s/app/#", id_dispositivo);
  Serial.printf("MQTT: topicos en juegito/%s/\n", id_dispositivo);
}

void publicar(topico_t topico, const char *payload) {
  mensaje_mqtt_t msg;
  msg.topico = topico;
  strlcpy(msg.payload, payload, sizeof(msg.payload));
  xQueueSend(cola_mqtt, &msg, 0);
}

void publicar_nivel() {
  char payload[LARGO_PAYLOAD];
  snprintf(payload, sizeof(payload),
           "{\"nivel\":%d,\"largo_secuencia\":%d,\"largo_inicial\":%d,"
           "\"dificultad\":\"%s\",\"timeout_ms\":%lu,\"vidas\":%d}",
           nivel, largo_secuencia, LARGO_INICIAL,
           dificultad_actual, timeout_ingreso, vidas);
  publicar(TOP_NIVEL, payload);
}

void publicar_toque(int elemento, bool correcto, int paso) {
  char payload[LARGO_PAYLOAD];
  snprintf(payload, sizeof(payload),
           "{\"elemento\":\"%s\",\"correcto\":%s,\"paso\":%d,\"de\":%d,\"vidas\":%d}",
           entradas[elemento].nombre, correcto ? "true" : "false",
           paso, largo_secuencia, vidas);
  publicar(TOP_TOQUE, payload);
}

void publicar_timeout(int paso) {
  char payload[LARGO_PAYLOAD];
  snprintf(payload, sizeof(payload), "{\"paso\":%d,\"de\":%d,\"vidas\":%d}",
           paso, largo_secuencia, vidas);
  publicar(TOP_TIMEOUT, payload);
}

void publicar_nivel_superado() {
  char payload[LARGO_PAYLOAD];
  snprintf(payload, sizeof(payload), "{\"nivel\":%d,\"vidas\":%d,\"segundos_video\":%d}",
           nivel, vidas, segundos_video);
  publicar(TOP_NIVEL_SUPERADO, payload);
}

void publicar_game_over() {
  char payload[LARGO_PAYLOAD];
  int  largo = snprintf(payload, sizeof(payload),
                        "{\"nivel\":%d,\"segundos_video\":%d,\"secuencia\":[",
                        nivel, segundos_video);

  for (int i = 0; i < largo_secuencia; i++) {
    largo += snprintf(payload + largo, sizeof(payload) - largo, "%s\"%s\"",
                      (i > 0) ? "," : "", entradas[secuencia[i]].nombre);
  }
  snprintf(payload + largo, sizeof(payload) - largo, "]}");
  publicar(TOP_GAME_OVER, payload);
}

void init_fsm() {
  estado_actual   = ESTADO_ESPERANDO_APP;
  evento_actual   = EV_CONTINUE;
}

// ============================================================
//  Acciones
// ============================================================

void apagar_leds() {
  digitalWrite(PIN_LED_ROJO,  LOW);
  digitalWrite(PIN_LED_AZUL,  LOW);
  digitalWrite(PIN_LED_VERDE, LOW);
}

void prender_leds() {
  digitalWrite(PIN_LED_ROJO,  HIGH);
  digitalWrite(PIN_LED_AZUL,  HIGH);
  digitalWrite(PIN_LED_VERDE, HIGH);
}

void entrar_idle() {
  apagar_leds();
  noTone(PIN_BUZZER);
  Serial.println("Listo. Esperando START...");
}

unsigned long leer_dificultad() {
  int valor = analogRead(PIN_POTENCIOMETRO);

  if (valor < LIMITE_FACIL)  return TIMEOUT_FACIL;
  if (valor < LIMITE_NORMAL) return TIMEOUT_NORMAL;
  return TIMEOUT_DIFICIL;
}

const char* nombre_dificultad(unsigned long timeout) {
  if (timeout == TIMEOUT_FACIL)  return "FACIL";
  if (timeout == TIMEOUT_NORMAL) return "NORMAL";
  return "DIFICIL";
}

void agregar_color() {
  if (largo_secuencia < MAX_SECUENCIA) {
    secuencia[largo_secuencia] = random(0, CANT_ELEMENTOS);
    largo_secuencia++;
  } else {
    Serial.println("Se alcanzo el largo maximo de la secuencia");
  }
}

void subir_dificultad() {
  timeout_ingreso = timeout_ingreso * FACTOR_NIVEL / 100;
  if (timeout_ingreso < TIMEOUT_MINIMO) {
    timeout_ingreso = TIMEOUT_MINIMO;
  }
}

void anunciar_nivel() {
  Serial.printf("Nivel %d: %d colores, %lu ms por toque\n",
                nivel, largo_secuencia, timeout_ingreso);
  publicar_nivel();
}

void iniciar_partida() {
  vidas = VIDAS_INICIALES;
  nivel = 1;
  segundos_video = 0;

  xSemaphoreTake(mutex_secuencia, portMAX_DELAY);
  largo_secuencia = 0;
  timeout_ingreso = leer_dificultad();
  for (int i = 0; i < LARGO_INICIAL; i++) {
    agregar_color();
  }
  xSemaphoreGive(mutex_secuencia);

  dificultad_actual = nombre_dificultad(timeout_ingreso);
  anunciar_nivel();
  generar_evento(EV_CALCULO_LISTO);
}

void preparar_nuevo_nivel() {
  nivel++;

  xSemaphoreTake(mutex_secuencia, portMAX_DELAY);
  agregar_color();
  subir_dificultad();
  xSemaphoreGive(mutex_secuencia);

  anunciar_nivel();
  generar_evento(EV_CALCULO_LISTO);
}

void perder_vida() {
  vidas--;
  Serial.printf("Vidas restantes: %d\n", vidas);
  generar_evento((vidas > 0) ? EV_VIDAS_MAYOR_CERO : EV_VIDAS_CERO);
}

void sumar_segundos_video() {
  segundos_video += SEGUNDOS_POR_NIVEL;
  Serial.printf("Segundos de video acumulados: %d\n", segundos_video);
}

void prender_elemento(int elemento) {
  switch (elemento) {
    case ELEM_ROJO:
      digitalWrite(PIN_LED_ROJO, HIGH);
      break;
    case ELEM_AZUL:
      digitalWrite(PIN_LED_AZUL, HIGH);
      break;
    case ELEM_VERDE:
      digitalWrite(PIN_LED_VERDE, HIGH);
      break;
    case ELEM_SENSOR:
      prender_leds();
      break;
  }
}

void sonar_elemento(int elemento) {
  switch (elemento) {
    case ELEM_ROJO:
      tone(PIN_BUZZER, TONO_ROJO);
      break;
    case ELEM_AZUL:
      tone(PIN_BUZZER, TONO_AZUL);
      break;
    case ELEM_VERDE:
      tone(PIN_BUZZER, TONO_VERDE);
      break;
    case ELEM_SENSOR:
      tone(PIN_BUZZER, TONO_SENSOR);
      break;
  }
}

void mostrar_toque(int elemento) {
  prender_elemento(elemento);
  sonar_elemento(elemento);
  vTaskDelay(pdMS_TO_TICKS(DURACION_FEEDBACK_MS));
  apagar_leds();
  noTone(PIN_BUZZER);
}

void sonar_error() {
  tone(PIN_BUZZER, TONO_ERROR);
  for (int i = 0; i < 2; i++) {
    prender_leds();
    vTaskDelay(pdMS_TO_TICKS(150));
    apagar_leds();
    vTaskDelay(pdMS_TO_TICKS(150));
  }
  noTone(PIN_BUZZER);
}

void sonar_recompensa() {
  const int notas[] = { TONO_ROJO, TONO_VERDE, TONO_SENSOR };

  for (int i = 0; i < 3; i++) {
    tone(PIN_BUZZER, notas[i]);
    prender_leds();
    vTaskDelay(pdMS_TO_TICKS(120));
    apagar_leds();
    vTaskDelay(pdMS_TO_TICKS(40));
  }
  noTone(PIN_BUZZER);
}

void sonar_game_over() {
  const int notas[] = { TONO_VERDE, TONO_AZUL, TONO_ROJO, TONO_ERROR };

  for (int i = 0; i < 4; i++) {
    tone(PIN_BUZZER, notas[i]);
    vTaskDelay(pdMS_TO_TICKS(250));
  }
  noTone(PIN_BUZZER);
}

void reproducir_secuencia() {
  int copia[MAX_SECUENCIA];
  int largo;
  unsigned long timeout;

  xSemaphoreTake(mutex_secuencia, portMAX_DELAY);
  largo   = largo_secuencia;
  timeout = timeout_ingreso;
  for (int i = 0; i < largo; i++) {
    copia[i] = secuencia[i];
  }
  xSemaphoreGive(mutex_secuencia);

  Serial.printf("Reproduciendo secuencia de largo %d\n", largo);
  apagar_leds();
  vTaskDelay(pdMS_TO_TICKS(50));

  for (int i = 0; i < largo; i++) {
    prender_elemento(copia[i]);
    sonar_elemento(copia[i]);
    vTaskDelay(pdMS_TO_TICKS(timeout / 4));
    apagar_leds();
    noTone(PIN_BUZZER);
    vTaskDelay(pdMS_TO_TICKS(timeout / 5));
  }
  generar_evento(EV_SECUENCIA_COMPLETA);
}

// ============================================================
//  Tareas de FreeRTOS
// ============================================================

bool leer_entrada(const entrada_t *entrada) {
  if (entrada->elemento == ELEM_SENSOR) {
    return analogRead(entrada->pin) > UMBRAL_PRESION;
  }
  return digitalRead(entrada->pin) == LOW;
}

void tarea_boton(void *param) {
  entrada_t *entrada = (entrada_t *) param;
  bool apretado = false;
  unsigned long visto_apretado = 0;

  while (true) {
    if (leer_entrada(entrada)) {
      visto_apretado = millis();
      if (!apretado) {
        apretado = true;
        Serial.printf("Toque: %s\n", entrada->nombre);
        xQueueSend(cola_botones, &entrada->elemento, 0);
      }
    } else if (apretado && millis() - visto_apretado >= ANTIRREBOTE_MS) {
      apretado = false;
    }

    vTaskDelay(pdMS_TO_TICKS(PERIODO_BOTONES_MS));
  }
}


void jugar_juegito(void *param) {
  while (true) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    xQueueReset(cola_botones);
    Serial.println("Tu turno: repeti la secuencia");

    xSemaphoreTake(mutex_secuencia, portMAX_DELAY);
    unsigned long timeout = timeout_ingreso;
    xSemaphoreGive(mutex_secuencia);

    int paso = 0;
    bool turno_terminado = false;

    while (!turno_terminado) {
      int elemento;
      evento_t ev;
      int posicion = paso + 1;

      if (xQueueReceive(cola_botones, &elemento, pdMS_TO_TICKS(timeout)) == pdFALSE) {
        ev = EV_TIMEOUT;
        elemento = SIN_ELEMENTO;
        turno_terminado = true;
      } else {
        
        xSemaphoreTake(mutex_secuencia, portMAX_DELAY);
        bool incorrecto = (elemento != secuencia[paso]);
        bool ultimo     = (paso + 1 == largo_secuencia);
        xSemaphoreGive(mutex_secuencia);

        if (incorrecto) {
          ev = EV_INGRESO_INCORRECTO;
          turno_terminado = true;
        } else if (ultimo) {
          ev = EV_NIVEL_SUPERADO;
          turno_terminado = true;
        } else {
          ev = EV_INGRESO_CORRECTO;
          paso++;
        }
      }

      enviar_mensaje(ev, elemento, posicion);
    }
  }
}

void tarea_sonido(void *param) {
  pedido_sonido_t pedido;

  while (true) {
    xQueueReceive(cola_sonidos, &pedido, portMAX_DELAY);

    switch (pedido.tipo) {
      case SONIDO_TOQUE:
        mostrar_toque(pedido.elemento);
        break;
      case SONIDO_ERROR:
        sonar_error();
        break;
      case SONIDO_RECOMPENSA:
        sonar_recompensa();
        break;
      case SONIDO_GAME_OVER:
        sonar_game_over();
        break;
      case SONIDO_SECUENCIA:
        reproducir_secuencia();
        break;
    }
  }
}

void al_recibir_mqtt(char *topico, byte *payload, unsigned int largo) {
  if (strcmp(topico, topicos[TOP_APP_START]) == 0) {
    generar_evento(EV_BOTON_START);
  } else if (strcmp(topico, topicos[TOP_APP_CONEXION]) == 0 &&
             largo == 6 && memcmp(payload, "online", 6) == 0) {
    generar_evento(EV_APP_CONECTADA);
  }
}

bool conectar_mqtt() {
  char id_cliente[32];
  snprintf(id_cliente, sizeof(id_cliente), "juegito-esp32-%s", id_dispositivo);

  if (!cliente_mqtt.connect(id_cliente, NULL, NULL,
                            topicos[TOP_ESP32_CONEXION], 0, true, "offline")) {
    Serial.printf("MQTT: fallo la conexion (estado %d)\n", cliente_mqtt.state());
    return false;
  }
  cliente_mqtt.publish(topicos[TOP_ESP32_CONEXION], "online", true);
  cliente_mqtt.subscribe(topico_app_todos, 1);
  Serial.println("MQTT: conectado");
  return true;
}

void tarea_mqtt(void *param) {
  mensaje_mqtt_t msg;

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  cliente_mqtt.setServer(MQTT_BROKER, MQTT_PUERTO);
  cliente_mqtt.setBufferSize(MQTT_BUFFER);
  cliente_mqtt.setCallback(al_recibir_mqtt);

  while (true) {
    if (WiFi.status() != WL_CONNECTED) {
      vTaskDelay(pdMS_TO_TICKS(500));  
      continue;
    }
    if (!cliente_mqtt.connected() && !conectar_mqtt()) {
      vTaskDelay(pdMS_TO_TICKS(2000));
      continue;
    }
    cliente_mqtt.loop();

    if (xQueueReceive(cola_mqtt, &msg, pdMS_TO_TICKS(10)) == pdTRUE) {
      cliente_mqtt.publish(topicos[msg.topico], msg.payload, msg.topico == TOP_NIVEL);
    }
  }
}

void empezar_turno() {
  xTaskNotifyGive(handle_jugar);
}

void empezar_descanso() {
  Serial.printf("Nivel superado! Recompensa de %d ms\n", RECOMPENSA_MS);
  pedir_sonido(SONIDO_RECOMPENSA);
  xTimerStart(timer_descanso, 0);
}

void fin_recompensa(TimerHandle_t timer) {
  generar_evento(EV_TIMER_TICK);
}

// ---------------- Deteccion de eventos ----------------
evento_t leer_serial() {
  if (Serial.available() > 0) {
    char c = Serial.read();
    if (c == 'c') return EV_APP_CONECTADA;
    if (c == 's') return EV_BOTON_START;
  }
  return EV_CONTINUE;
}

// ---------------- get_event() -----------------
evento_t get_event() {
  evento_t ev_serial = leer_serial();
  if (ev_serial != EV_CONTINUE) {
    return ev_serial;
  }

  mensaje_t msg;
  if (xQueueReceive(cola_eventos, &msg, 0) == pdTRUE) {
    elemento_ingresado = msg.elemento;
    paso_ingresado     = msg.paso;
    return msg.evento;
  }

  return EV_CONTINUE;
}

// ============================================================
//  Maquina de estados
// ============================================================

void fsm() {
  evento_actual = get_event();

  if (evento_actual != EV_CONTINUE) {
    Serial.print("Estado: ");
    Serial.print(nombres_estados[estado_actual]);
    Serial.print(" | Evento: ");
    Serial.println(nombres_eventos[evento_actual]);
  }

  switch (estado_actual) {

    case ESTADO_ESPERANDO_APP:
      switch (evento_actual) {
        case EV_APP_CONECTADA:
          entrar_idle();
          estado_actual = ESTADO_IDLE;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_IDLE:
      switch (evento_actual) {
        case EV_BOTON_START:
          iniciar_partida();
          estado_actual = ESTADO_NUEVO_NIVEL;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_NUEVO_NIVEL:
      switch (evento_actual) {
        case EV_CALCULO_LISTO:
          pedir_sonido(SONIDO_SECUENCIA);
          estado_actual = ESTADO_REPRODUCIENDO_SECUENCIA;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_REPRODUCIENDO_SECUENCIA:
      switch (evento_actual) {
        case EV_SECUENCIA_COMPLETA:
          empezar_turno();
          estado_actual = ESTADO_ESPERANDO_INGRESO;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_ESPERANDO_INGRESO:
      switch (evento_actual) {
        case EV_INGRESO_CORRECTO:
          pedir_sonido(SONIDO_TOQUE, elemento_ingresado);
          publicar_toque(elemento_ingresado, true, paso_ingresado);
          estado_actual = ESTADO_ESPERANDO_INGRESO;
          break;
        case EV_NIVEL_SUPERADO:
          pedir_sonido(SONIDO_TOQUE, elemento_ingresado);
          publicar_toque(elemento_ingresado, true, paso_ingresado);
          sumar_segundos_video();
          publicar_nivel_superado();
          empezar_descanso();
          estado_actual = ESTADO_RECOMPENSA;
          break;
        case EV_INGRESO_INCORRECTO:
          pedir_sonido(SONIDO_TOQUE, elemento_ingresado);
          pedir_sonido(SONIDO_ERROR);
          perder_vida();
          publicar_toque(elemento_ingresado, false, paso_ingresado);
          estado_actual = ESTADO_EVALUANDO_VIDAS;
          break;
        case EV_TIMEOUT:
          pedir_sonido(SONIDO_ERROR);
          perder_vida();
          publicar_timeout(paso_ingresado);
          estado_actual = ESTADO_EVALUANDO_VIDAS;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_EVALUANDO_VIDAS:
      switch (evento_actual) {
        case EV_VIDAS_MAYOR_CERO:
          pedir_sonido(SONIDO_SECUENCIA);
          estado_actual = ESTADO_REPRODUCIENDO_SECUENCIA;
          break;
        case EV_VIDAS_CERO:
          pedir_sonido(SONIDO_GAME_OVER);
          publicar_game_over();
          Serial.println("GAME OVER. Apreta 's' para jugar de nuevo");
          estado_actual = ESTADO_GAME_OVER;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_RECOMPENSA:
      switch (evento_actual) {
        case EV_TIMER_TICK:
          preparar_nuevo_nivel();
          estado_actual = ESTADO_NUEVO_NIVEL;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    case ESTADO_GAME_OVER:
      switch (evento_actual) {
        case EV_BOTON_START:
          iniciar_partida();
          estado_actual = ESTADO_NUEVO_NIVEL;
          break;
        case EV_CONTINUE:
          break;
        default:
          break;
      }
      break;

    default:
      break;
  }
}

// ---------------- ESP32 ----------------
void setup() {
  Serial.begin(115200);

  pinMode(PIN_LED_ROJO,  OUTPUT);
  pinMode(PIN_LED_AZUL,  OUTPUT);
  pinMode(PIN_LED_VERDE, OUTPUT);

  pinMode(PIN_BTN_ROJO,  INPUT_PULLUP);
  pinMode(PIN_BTN_AZUL,  INPUT_PULLUP);
  pinMode(PIN_BTN_VERDE, INPUT_PULLUP);

  pinMode(PIN_BUZZER, OUTPUT);


  cola_botones = xQueueCreate(CANT_ELEMENTOS_COLA, sizeof(int));
  cola_eventos = xQueueCreate(CANT_ELEMENTOS_COLA, sizeof(mensaje_t));
  cola_sonidos = xQueueCreate(CANT_ELEMENTOS_COLA, sizeof(pedido_sonido_t));
  cola_mqtt    = xQueueCreate(CANT_ELEMENTOS_COLA, sizeof(mensaje_mqtt_t));
  mutex_secuencia = xSemaphoreCreateMutex();
  armar_topicos();
  for (int i = 0; i < CANT_ELEMENTOS; i++) {
    xTaskCreate(tarea_boton, entradas[i].nombre, 4096, &entradas[i], 2, NULL);
  }
  xTaskCreate(jugar_juegito, "jugar", 4096, NULL, 2, &handle_jugar);
  xTaskCreate(tarea_sonido, "sonido", 4096, NULL, 2, NULL);
  xTaskCreate(tarea_mqtt, "mqtt", 8192, NULL, 2, NULL);
  
  timer_descanso = xTimerCreate("recompensa", pdMS_TO_TICKS(RECOMPENSA_MS),
                                  pdFALSE, NULL, fin_recompensa);

  init_fsm();
}

void loop() {
  fsm();
}
