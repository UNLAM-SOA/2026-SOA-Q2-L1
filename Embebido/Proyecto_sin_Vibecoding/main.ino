#include <Arduino.h>

// ---------------- Pines ----------------
// LEDs
#define PIN_LED_ROJO        26
#define PIN_LED_AZUL        27
#define PIN_LED_VERDE       14

// Pulsadores a GND: INPUT_PULLUP, apretado = LOW
#define PIN_BTN_ROJO        18
#define PIN_BTN_AZUL        19
#define PIN_BTN_VERDE       21

// GPIO 34 y 35: solo entrada (ADC1)
#define PIN_POTENCIOMETRO   34
#define PIN_SENSOR_PRESION  35   // Simulado con el pot2 en Wokwi

// Actuador
#define PIN_BUZZER          4

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
  EV_CONTINUE, // Este evento se usa cuando no paso nada nuevo
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

// El pot1 elige el punto de partida y despues cada nivel acorta el tiempo
#define FACTOR_NIVEL     90     // % del tiempo que queda al pasar de nivel
#define TIMEOUT_MINIMO   600    // ms: por mas que suba el nivel, no baja de aca

unsigned long timeout_ingreso;

int vidas;
int nivel;
int secuencia[MAX_SECUENCIA];
int largo_secuencia;

// ---------------- FreeRTOS ----------------
#define PERIODO_BOTONES_MS    5      // Cada cuanto cada tarea_boton lee su boton
#define ANTIRREBOTE_MS        30     // Tiempo suelto para aceptar el proximo toque
#define DURACION_FEEDBACK_MS  200    // LED + tono al tocar un boton
#define UMBRAL_PRESION        3000   // Sensor (pot2) por encima de esto = presionado
#define RECOMPENSA_MS         2000   // Lo que dura la recompensa al pasar de nivel

QueueHandle_t cola_botones;
QueueHandle_t cola_eventos;
TaskHandle_t  handle_jugar;

unsigned long inicio_recompensa;

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

void agregar_color() {
  if (largo_secuencia < MAX_SECUENCIA) {
    secuencia[largo_secuencia] = random(0, CANT_ELEMENTOS);   // incluye el sensor
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
}

void iniciar_partida() {
  vidas = VIDAS_INICIALES;
  nivel = 1;
  largo_secuencia = 0;
  timeout_ingreso = leer_dificultad();

  for (int i = 0; i < LARGO_INICIAL; i++) {
    agregar_color();
  }
  anunciar_nivel();
}

void preparar_nuevo_nivel() {
  nivel++;
  agregar_color();
  subir_dificultad();
  anunciar_nivel();
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

void sonar_error() {
  tone(PIN_BUZZER, TONO_ERROR);
  for (int i = 0; i < 2; i++) {
    prender_leds();
    delay(150);
    apagar_leds();
    delay(150);
  }
  noTone(PIN_BUZZER);
}

void sonar_recompensa() {
  const int notas[] = { TONO_ROJO, TONO_VERDE, TONO_SENSOR };

  for (int i = 0; i < 3; i++) {
    tone(PIN_BUZZER, notas[i]);
    prender_leds();
    delay(120);
    apagar_leds();
    delay(40);
  }
  noTone(PIN_BUZZER);
}

void sonar_game_over() {
  const int notas[] = { TONO_VERDE, TONO_AZUL, TONO_ROJO, TONO_ERROR };

  for (int i = 0; i < 4; i++) {
    tone(PIN_BUZZER, notas[i]);
    delay(250);
  }
  noTone(PIN_BUZZER);
}

void reproducir_secuencia() {
  Serial.printf("Reproduciendo secuencia de largo %d\n", largo_secuencia);
  apagar_leds();
  delay(50);

  for (int i = 0; i < largo_secuencia; i++) {
    prender_elemento(secuencia[i]);
    sonar_elemento(secuencia[i]);
    delay(timeout_ingreso / 4);
    apagar_leds();
    noTone(PIN_BUZZER);
    delay(timeout_ingreso / 5);
  }
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

    int paso = 0;
    bool turno_terminado = false;

    while (!turno_terminado) {
      int elemento;
      evento_t ev;

      if (xQueueReceive(cola_botones, &elemento, pdMS_TO_TICKS(timeout_ingreso)) == pdFALSE) {
        ev = EV_TIMEOUT;
        turno_terminado = true;
      } else {
        prender_elemento(elemento);
        sonar_elemento(elemento);
        vTaskDelay(pdMS_TO_TICKS(DURACION_FEEDBACK_MS));
        apagar_leds();
        noTone(PIN_BUZZER);

        if (elemento != secuencia[paso]) {
          ev = EV_INGRESO_INCORRECTO;
          turno_terminado = true;
        } else if (paso + 1 == largo_secuencia) {
          ev = EV_NIVEL_SUPERADO;
          turno_terminado = true;
        } else {
          ev = EV_INGRESO_CORRECTO;
          paso++;
        }
      }

      xQueueSend(cola_eventos, &ev, portMAX_DELAY);
    }
  }
}

void empezar_turno() {
  xTaskNotifyGive(handle_jugar);
}

void empezar_recompensa() {
  Serial.printf("Nivel superado! Recompensa de %d ms\n", RECOMPENSA_MS);
  sonar_recompensa();
  inicio_recompensa = millis();
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

  evento_t ev_juego;
  if (xQueueReceive(cola_eventos, &ev_juego, 0) == pdTRUE) {
    return ev_juego;
  }

  if (estado_actual == ESTADO_EVALUANDO_VIDAS) {
    return (vidas > 0) ? EV_VIDAS_MAYOR_CERO : EV_VIDAS_CERO;
  }

  if (estado_actual == ESTADO_NUEVO_NIVEL) {
    return EV_CALCULO_LISTO;
  }

  if (estado_actual == ESTADO_REPRODUCIENDO_SECUENCIA) {
    return EV_SECUENCIA_COMPLETA;
  }

  if (estado_actual == ESTADO_RECOMPENSA &&
      millis() - inicio_recompensa >= RECOMPENSA_MS) {
    return EV_TIMER_TICK;
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
          reproducir_secuencia();
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
        case EV_NIVEL_SUPERADO:
          empezar_recompensa();
          estado_actual = ESTADO_RECOMPENSA;
          break;
        case EV_INGRESO_INCORRECTO:
        case EV_TIMEOUT:
          sonar_error();
          vidas--;
          Serial.printf("Vidas restantes: %d\n", vidas);
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
          reproducir_secuencia();
          estado_actual = ESTADO_REPRODUCIENDO_SECUENCIA;
          break;
        case EV_VIDAS_CERO:
          sonar_game_over();
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

  // Prioridad 2: mas que loop(), que tiene 1
  cola_botones = xQueueCreate(10, sizeof(int));
  cola_eventos = xQueueCreate(10, sizeof(evento_t));
  for (int i = 0; i < CANT_ELEMENTOS; i++) {
    xTaskCreate(tarea_boton, entradas[i].nombre, 4096, &entradas[i], 2, NULL);
  }
  xTaskCreate(jugar_juegito, "jugar", 4096, NULL, 2, &handle_jugar);

  init_fsm();
}

void loop() {
  fsm();
}
