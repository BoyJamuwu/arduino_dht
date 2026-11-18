# Integridad de datos, reconexión y almacenamiento local

Sistema de monitoreo de temperatura para cajas térmicas: Arduino UNO + DHT11 + HC-06 → App Android.

---

## 1. Integridad de los datos

Toda línea recibida por Bluetooth pasa por la clase `ValidadorTramas` (`IntegridadDatos.kt`) antes de ser aceptada como lectura.

### 1.1 Formatos aceptados

| Formato | Ejemplo | Garantía |
|---|---|---|
| **Trama verificada** (recomendado) | `$T=25.0,H=51.0,N=128*57` | Checksum + secuencia + rango + saltos |
| Texto libre (formato actual) | `ALERTA HUMEDAD \| Temp: 25.0 C \| Humedad: 51.0 %` | Rango + saltos |

En la trama verificada:

- `T`: temperatura, `H`: humedad, `N`: número de secuencia.
- `*4C`: checksum **XOR** (estilo NMEA) de todos los caracteres entre `$` y `*`, en hexadecimal.

Si llegan tramas verificadas, durante los 15 s siguientes la app ignora los valores en texto libre. Así la misma medición no se cuenta dos veces y se conserva la de mayor garantía. Los textos `ALERTA` y `-> OK` siguen usándose para mostrar el estado del sistema.

### 1.2 Controles aplicados (en orden)

| # | Control | Qué detecta | Acción |
|---|---|---|---|
| 1 | Largo máximo de línea (160 bytes) | Ruido o datos basura en el canal | Se descarta la línea |
| 2 | Checksum XOR | Bytes alterados en la transmisión | Se rechaza (`CHECKSUM`) |
| 3 | Formato / campos | Trama incompleta | Se rechaza (`FORMATO`) |
| 4 | Número de secuencia | Tramas perdidas entre dos recibidas | Se cuentan como "Perdidas" |
| 5 | Rango físico (-20…60 °C, 0…100 %HR) | Lecturas imposibles o fallas del sensor | Se rechaza (`RANGO`) |
| 6 | Filtro de saltos (>8 °C o >25 %HR en 15 s) | Picos de ruido (un DHT11 no cambia tan rápido) | Se rechaza (`SALTO`) |
| 7 | Promedio de ventana de 5 min | Variación puntual | Se guarda el promedio, no una muestra suelta |
| 8 | Hash SHA-256 encadenado en SQLite | Modificación o borrado de registros guardados | La pantalla Historial lo informa |

En la pantalla **Control** se ven en vivo los contadores: válidas, rechazadas, perdidas y reconexiones, junto con el motivo del último rechazo.

### 1.3 Cambio recomendado en el Arduino

Agregar esta función al sketch y llamarla en cada medición. Reemplaza el `println` que hoy envía `Temp:` y `Humedad:`. Los mensajes de estado (`ALERTA ...` / `-> OK`) pueden seguir enviándose como hasta ahora.

```cpp
// Ajustar "BT" al nombre del SoftwareSerial (o Serial) conectado al HC-06
unsigned int secuencia = 0;

void enviarTrama(float t, float h) {
  if (isnan(t) || isnan(h)) return;          // lectura fallida del DHT11: no se envía

  char tStr[8], hStr[8], cuerpo[40], trama[48];
  dtostrf(t, 1, 1, tStr);                    // el sprintf de AVR no soporta %f
  dtostrf(h, 1, 1, hStr);
  snprintf(cuerpo, sizeof(cuerpo), "T=%s,H=%s,N=%u", tStr, hStr, secuencia++);

  byte cs = 0;
  for (char *p = cuerpo; *p; p++) cs ^= *p;  // checksum XOR

  snprintf(trama, sizeof(trama), "$%s*%02X", cuerpo, cs);
  BT.println(trama);                         // ej: $T=25.0,H=51.0,N=128*57
}
```

> Importante: el Arduino debe enviar algo **al menos cada 10 s**. La app considera caído el enlace tras 30 s sin datos (ver 2.2).

---

## 2. Mecanismo de reconexión Arduino ↔ App

Implementado en `BluetoothManager.kt`.

### 2.1 Estados de la conexión

```
DESCONECTADO ──conectar──► CONECTANDO ──ok──► CONECTADO
      ▲                         │                 │  caída detectada
      │                       falla               ▼
      │                         ▼           RECONECTANDO ◄──┐
  desconectar             FALLIDO ◄──8 fallos──┤  falla      │
  (usuario)                  │                 └─────────────┘
                         Reintentar
```

### 2.2 Cómo se detecta la caída

1. **Lectura**: el canal devuelve error o fin de flujo (HC-06 apagado o fuera de alcance).
2. **Envío**: falla al mandar un comando (por ejemplo, "A").
3. **Vigilancia (watchdog)**: cada 5 s se revisa si pasaron más de **30 s sin recibir datos**. Detecta el caso en que el socket queda "abierto" pero el Arduino se colgó.

Solo la primera de estas señales dispara la reconexión. Se usa un contador de generación atómico, así no se abren dos reconexiones en paralelo.

### 2.3 Política de reintentos

- Espera exponencial: **1, 2, 4, 8, 16, 30, 30, 30 s** (8 intentos, ~2 min en total).
- Si todos fallan, el estado pasa a `FALLIDO` y se muestra el botón **Reintentar**.
- Durante la reconexión, **Reintentar ya** salta la espera y **Desconectar** la cancela.
- Una desconexión hecha por el usuario nunca dispara la reconexión automática.
- Cada evento (`CONEXION_PERDIDA`, `RECONEXION_OK`, `RECONEXION_AGOTADA`…) queda en la bitácora SQLite.

---

## 3. Almacenamiento temporal (SQLite)

Implementado en `LecturasDb.kt` (con `SQLiteOpenHelper`, sin librerías externas) y `RegistroPeriodico.kt`.

### 3.1 Esquema

```sql
CREATE TABLE lecturas (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha_ms    INTEGER NOT NULL,
    temperatura REAL    NOT NULL CHECK (temperatura BETWEEN -40 AND 80),
    humedad     REAL    NOT NULL CHECK (humedad BETWEEN 0 AND 100),
    verificada  INTEGER NOT NULL DEFAULT 0 CHECK (verificada IN (0, 1)),
    hash        TEXT    NOT NULL           -- SHA-256(hash_anterior | campos)
);

CREATE TABLE eventos (                      -- bitácora de auditoría
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fecha_ms INTEGER NOT NULL,
    tipo TEXT NOT NULL,
    detalle TEXT
);
```

### 3.2 Cómo se guarda

- Mientras hay conexión, se acumulan las lecturas válidas. **Cada 5 minutos** se guarda **el promedio** de esa ventana.
- El primer registro se guarda con la primera lectura válida, así no hay que esperar 5 min para ver datos.
- Si hubo un corte de más de 5 min, la ventana se reinicia y no se mezclan datos viejos con nuevos.
- `verificada = 1` solo si todas las lecturas de la ventana llegaron con checksum correcto.
- Las escrituras se hacen en un único hilo, dentro de transacciones, con la base en modo WAL.

### 3.3 Retención

| Tabla | Límite |
|---|---|
| `lecturas` | 30 días o 10.000 filas (lo que ocurra primero) |
| `eventos` | 1.000 filas |

### 3.4 Pantalla Historial

- Gráfico de temperatura y gráfico de humedad, separados (cada uno con su escala). Al tocar o arrastrar se ve el valor exacto.
- Tabla con los **últimos 10 registros** (#, fecha, °C, %HR, tipo de trama).
- Botón **Mostrar más** que carga de a 10 registros. El gráfico se amplía con los registros cargados.
- Al abrir la pantalla se recalcula la cadena de hashes y se indica si la base está íntegra.
