package com.softbenur.colados.ingest;

import com.softbenur.colados.contracts.TagReadBatch;

/**
 * Evento interno: un lote acaba de entrar y persistirse.
 *
 * <p>Los módulos se hablan publicando hechos, nunca llamándose por método. Hoy lo
 * entrega Spring en proceso; el día que hubiera un broker, el resto de módulos no
 * notarían la diferencia (ADR-0011).
 */
public record BatchIngested(TagReadBatch batch, boolean duplicate) {}
