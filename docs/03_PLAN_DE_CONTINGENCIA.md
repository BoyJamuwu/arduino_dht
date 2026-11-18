# Plan de contingencia

**Sistema:** Monitoreo de temperatura para cajas térmicas (Arduino UNO + DHT11 + HC-06 + App Android)
**Objetivo:** mantener la vigilancia de las condiciones de la caja térmica y no perder registros cuando falle algún componente.

---

## 1. Objetivos de recuperación

| Indicador | Objetivo |
|---|---|
| Tiempo máximo sin detectar una caída del enlace | 30 s |
| Tiempo de reconexión automática (si el Arduino vuelve) | ≤ 2 min |
| Pérdida máxima de registros históricos | 1 intervalo (5 min) |
| Datos guardados disponibles sin conexión | 30 días |

## 2. Roles

| Rol | Responsabilidad |
|---|---|
| Operador | Revisar la app, atender las alertas y ejecutar los procedimientos de esta guía |
| Responsable técnico | Hardware, firmware del Arduino y mantenimiento de la app |

## 3. Escenarios y procedimientos

### E1. Pérdida de conexión Bluetooth
**Detección:** en Control el estado cambia a *Reconectando…* (punto ámbar) y la bitácora registra `CONEXION_PERDIDA`.
**Respuesta automática:** 8 reintentos con espera creciente (1 s → 30 s).
**Acciones del operador:**
1. Acercar el celular a la caja (menos de 10 m y sin obstáculos metálicos).
2. Confirmar que el LED del HC-06 parpadea (sin enlace) o queda fijo (con enlace).
3. Si el estado pasa a *Sin conexión*, tocar **Reintentar**.
4. Si sigue fallando, desactivar y volver a activar el Bluetooth del celular y luego tocar **Reintentar**.

### E2. El Arduino deja de enviar datos (cuelgue)
**Detección:** pasan 30 s sin datos → la vigilancia fuerza la reconexión. Se registra `CONEXION_PERDIDA: Sin datos durante N s`.
**Acciones:**
1. Revisar la alimentación del Arduino (LED ON).
2. Reiniciar el Arduino (botón RESET o desconectar y conectar).
3. La app se reconecta sola cuando el HC-06 vuelve a estar disponible.

### E3. Falla del sensor DHT11 o lecturas incoherentes
**Detección:** aumenta el contador *Rechazadas* en Control. El último rechazo dice `RANGO` o `SALTO`.
**Acciones:**
1. Revisar el cableado del DHT11 (VCC, datos, GND y resistencia pull-up).
2. Comparar con un termómetro de referencia.
3. Si el sensor está dañado, reemplazarlo. Los registros con datos rechazados nunca se guardan.

### E4. Datos corruptos en la transmisión
**Detección:** aumenta *Rechazadas* por `CHECKSUM` o aparecen *Perdidas*.
**Acciones:**
1. Alejar fuentes de interferencia (microondas, routers Wi-Fi a 2,4 GHz).
2. Revisar el divisor de tensión en el pin RX del HC-06 (3,3 V).
3. Si se repite, reducir la distancia o cambiar el módulo.

### E5. Temperatura fuera de rango (alerta del proceso)
**Detección:** el LED rojo o azul se enciende en el Arduino; en la app el chip pasa a *⚠ Alerta*.
**Acciones:**
1. Verificar que la caja térmica esté cerrada y revisar el estado del refrigerante o los geles.
2. Anotar la hora del evento. El Historial guarda el promedio de cada intervalo como evidencia.
3. Si la alerta dura más de 2 intervalos (10 min), avisar al responsable para decidir sobre el contenido de la caja.

### E6. Celular sin batería, app cerrada o celular perdido
**Impacto:** no se registran lecturas mientras la app no está activa. El Arduino sigue indicando con los LED.
**Acciones:**
1. Mantener el celular cargado durante el transporte (power bank).
2. Usar un segundo celular de respaldo con la app instalada. Basta con emparejar el HC-06 y conectar.
3. Si se pierde el celular: los datos no salen del equipo (backups deshabilitados) y la app pide credenciales.

### E7. Base de datos alterada o dañada
**Detección:** en Historial aparece *⚠ N de M registros no coinciden con su hash*, o `DB_ERROR` en la bitácora.
**Acciones:**
1. No borrar la app. Registrar el incidente con fecha y hora.
2. Los registros posteriores siguen guardándose y encadenándose.
3. Si la base no abre, reinstalar la app. El historial se pierde, pero el monitoreo continúa.

### E8. Acceso no autorizado (intentos de login)
**Detección:** aviso de bloqueo en el login y eventos `LOGIN_FALLIDO` / `LOGIN_BLOQUEADO`.
**Acciones:**
1. Esperar el tiempo de bloqueo.
2. Si no fue el operador, avisar al responsable técnico y revisar quién tuvo el celular.

## 4. Matriz resumen

| Escenario | Probabilidad | Impacto | Respuesta automática | Tiempo de recuperación |
|---|---|---|---|---|
| E1 Pérdida de Bluetooth | Alta | Alto | Sí (reconexión) | < 2 min |
| E2 Cuelgue del Arduino | Media | Alto | Detección (30 s) | Hasta un reinicio manual |
| E3 Falla del DHT11 | Baja | Alto | Rechazo de datos | Hasta el reemplazo |
| E4 Datos corruptos | Media | Medio | Rechazo por checksum | Inmediato |
| E5 Fuera de rango | Media | Alto | LED + chip de alerta | Según la acción en la caja |
| E6 Celular no disponible | Media | Alto | No | Hasta usar el celular de respaldo |
| E7 DB alterada | Baja | Medio | Detección por hash | Inmediato (sigue registrando) |
| E8 Acceso indebido | Baja | Medio | Bloqueo progresivo | 1–15 min |

## 5. Pruebas del plan

| Prueba | Cómo se hace | Resultado esperado |
|---|---|---|
| Corte de enlace | Desconectar la alimentación del HC-06 por 20 s | *Reconectando…* y luego *Conectado*; el contador Reconex. suma 1 |
| Cuelgue | Dejar de enviar datos desde el Arduino 40 s | La vigilancia fuerza la reconexión |
| Checksum | Enviar `$T=25.0,H=50.0,N=1*00` desde un terminal serie | Rechazadas +1, motivo CHECKSUM |
| Bloqueo | 5 contraseñas incorrectas | Aviso de bloqueo de 1 min |
| Retención | Revisar el Historial después de 30 días | Registros más antiguos eliminados |

Repetir las pruebas antes de cada uso en terreno y después de cada cambio en el firmware o en la app.
