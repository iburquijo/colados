package com.softbenur.colados.contracts;

import java.time.Instant;

/**
 * Latido de un lector, publicado en {@code .../status} con mensaje retenido.
 *
 * <p>El mismo topic lleva el <i>Last Will and Testament</i>: si el lector pierde la
 * conexión, el broker publica {@code OFFLINE} por él (ADR-0001). Así la caída de un
 * dispositivo es un evento del sistema y no un silencio que nadie interpreta.
 */
public record ReaderStatus(
        String schema,
        String readerId,
        State status,
        long uptimeS,
        long readsLastMinute,
        Instant at) {

    public static final String SCHEMA = "colados.readerstatus.v1";

    public enum State { ONLINE, OFFLINE }

    /** Payload del LWT: lo publica el broker, no el lector. */
    public static ReaderStatus lastWill(String readerId) {
        return new ReaderStatus(SCHEMA, readerId, State.OFFLINE, 0, 0, null);
    }
}
