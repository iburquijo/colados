package com.softbenur.colados.contracts;

import java.time.Instant;

/**
 * Una lectura de tag: lo único que un lector RFID físico puede saber.
 *
 * <p>No lleva {@code coilId}, {@code slotId} ni {@code zoneId} a propósito. El lector ve
 * un código y una potencia de señal; qué significa ese código lo decide el backend
 * contra el registro de tags (ADR-0006).
 *
 * @param epc    código del tag. Puede ser de bobina o de ubicación: el lector no lo sabe.
 * @param rssi   potencia de señal en dBm. Negativo; más cerca de 0 es más fuerte.
 * @param readAt instante según el reloj DEL LECTOR, que puede ir desfasado.
 */
public record TagRead(String epc, double rssi, Instant readAt) {

    public TagRead {
        if (epc == null || epc.isBlank()) {
            throw new IllegalArgumentException("epc obligatorio");
        }
        if (readAt == null) {
            throw new IllegalArgumentException("readAt obligatorio");
        }
    }
}
