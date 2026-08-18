package com.softbenur.colados.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/**
 * Informe de inventario de un lector: todas las lecturas de una ventana de ~200 ms
 * (ADR-0008).
 *
 * <p>Los lectores UHF reales no emiten lecturas sueltas, agrupan. Un lote <b>vacío es
 * información válida</b>: "he mirado y no había nada", que es distinto de no haber
 * publicado.
 *
 * @param batchSeq contador monótono por lector. Clave de idempotencia junto a
 *                 {@code readerId}, y detección de huecos: si falta uno, hubo pérdida.
 */
public record TagReadBatch(
        String schema,
        String plantId,
        String readerId,
        ReaderType readerType,
        long batchSeq,
        Instant windowStart,
        Instant windowEnd,
        String traceId,
        List<TagRead> reads) {

    public static final String SCHEMA = "colados.tagreadbatch.v1";

    public TagReadBatch {
        if (readerId == null || readerId.isBlank()) {
            throw new IllegalArgumentException("readerId obligatorio");
        }
        if (windowStart == null || windowEnd == null) {
            throw new IllegalArgumentException("ventana obligatoria");
        }
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd anterior a windowStart");
        }
        reads = reads == null ? List.of() : List.copyOf(reads);
    }

    /** Clave de idempotencia del lote (ADR-0008). */
    @JsonIgnore
    public String idempotencyKey() {
        return readerId + ":" + batchSeq;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return reads.isEmpty();
    }
}
