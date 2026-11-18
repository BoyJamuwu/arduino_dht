# Seguridad según ISO/IEC 27400:2022

**ISO/IEC 27400:2022, "Ciberseguridad — Seguridad y privacidad de IoT — Directrices"**, da recomendaciones de seguridad y privacidad para todo el ciclo de vida de un sistema IoT. Aplica a dispositivos, comunicaciones, aplicaciones y servicios. Su enfoque parte de identificar las fuentes de riesgo de un sistema IoT y aplicar controles a cada parte involucrada.

Este documento resume cómo se aplicó al proyecto: primero los riesgos y después los controles implementados.

> Nota: los dominios de la tabla agrupan las directrices de la norma de forma resumida para este proyecto. Para citar cláusulas exactas en el informe, consultar el texto oficial de la norma.

---

## 1. Alcance y activos

| Activo | Descripción |
|---|---|
| Dispositivo IoT | Arduino UNO + DHT11 + 3 LED + módulo HC-06 |
| Canal | Bluetooth clásico (SPP / RFCOMM) |
| Aplicación | App Android "Monitor Térmico" |
| Datos | Temperatura, humedad, fecha y bitácora de eventos (no hay datos personales) |
| Credenciales | Usuario operador de la app |

## 2. Análisis de riesgos

| # | Riesgo | Prob. | Impacto | Tratamiento |
|---|---|---|---|---|
| R1 | Datos alterados o con ruido en el canal Bluetooth | Media | Alto | Checksum, rango, filtro de saltos (control C4) |
| R2 | Pérdida de conexión durante el transporte | Alta | Alto | Reconexión automática + watchdog (C6) |
| R3 | Acceso no autorizado a la app | Media | Medio | Contraseña con hash + bloqueo + sesión (C1–C3) |
| R4 | Fuerza bruta sobre el login | Baja | Medio | Bloqueo progresivo (C2) |
| R5 | Modificación del historial guardado | Baja | Alto | Cadena de hashes SHA-256 (C5) |
| R6 | Extracción de datos por backup del celular | Baja | Medio | Backups deshabilitados (C8) |
| R7 | Emparejamiento de un dispositivo falso | Baja | Medio | Solo dispositivos emparejados con PIN (C7) |
| R8 | Saturación de memoria con datos basura | Baja | Medio | Límite de largo por línea (C4) |
| R9 | Pérdida de evidencia ante un incidente | Media | Medio | Bitácora de eventos (C9) |

## 3. Controles implementados

| Dominio de la norma | Control | Implementación en el código |
|---|---|---|
| Control de acceso | **C1** Contraseña sin texto plano | `Seguridad.kt`: PBKDF2-HMAC-SHA256, sal de 16 bytes, 20.000 iteraciones; comparación en tiempo constante; la contraseña se borra de memoria después de usarla |
| Control de acceso | **C2** Bloqueo por intentos fallidos | 5 fallos → bloqueo de 1 min; se duplica en cada nuevo bloqueo (máx. 15 min) |
| Control de acceso | **C3** Gestión de sesión | Sesión en memoria que expira tras 10 min sin actividad (`Sesion`, `BaseActivity`) |
| Integridad de datos | **C4** Validación de entrada | `ValidadorTramas`: checksum, formato, secuencia, rango físico, saltos bruscos; líneas > 160 bytes descartadas |
| Integridad de datos | **C5** Integridad del almacenamiento | Hash SHA-256 encadenado por registro; `CHECK` de rangos en SQL; transacciones |
| Disponibilidad / resiliencia | **C6** Recuperación del enlace | Reconexión con espera exponencial, vigilancia de 30 s sin datos, reintento manual |
| Seguridad de la comunicación | **C7** Dispositivos de confianza | Solo se listan dispositivos ya emparejados (pairing con PIN); permiso `BLUETOOTH_CONNECT` en tiempo de ejecución |
| Protección de datos | **C8** Datos solo en el dispositivo | `allowBackup=false`; reglas que excluyen la base de datos y las preferencias de backups y transferencias |
| Registro y monitoreo | **C9** Bitácora de auditoría | Tabla `eventos`: logins (ok/fallido/bloqueo), conexión, pérdida, reconexión, tramas rechazadas, errores de DB |
| Privacidad | **C10** Minimización de datos | Solo se guardan valores ambientales y la fecha; no hay datos personales ni ubicación |
| Ciclo de vida | **C11** Retención limitada | Lecturas: 30 días / 10.000 filas; eventos: 1.000 filas |

## 4. Riesgo residual y mejoras pendientes

| Limitación | Mejora propuesta |
|---|---|
| Bluetooth clásico del HC-06 solo cifra el enlace tras el pairing; el PIN de fábrica suele ser `1234` | Cambiar el PIN con `AT+PIN` y el nombre con `AT+NAME` |
| El checksum XOR detecta errores de transmisión, pero no la falsificación intencional | Usar HMAC con clave compartida (requiere un microcontrolador con más memoria) |
| La credencial es única y está fija en la app | Alta de usuario en el primer uso y almacenamiento en Android Keystore |
| La base SQLite no está cifrada | SQLCipher o cifrado por campo con Keystore |
| Sin actualización remota del firmware del Arduino | Procedimiento documentado de actualización por USB |
