package com.softbenur.colados.ingest;

import java.time.Instant;

/**
 * Una lectura tal y como se persiste.
 *
 * <p>{@code readAt} viene del reloj del lector y {@code receivedAt} del servidor. Se
 * guardan por separado a propósito: el desfase de reloj es un fenómeno real y hay que
 * poder medirlo, no ocultarlo.
 */
public record RawRead(
        String readerId,
        String readerType,
        long batchSeq,
        String epc,
        double rssi,
        Instant readAt,
        String traceId) {}
