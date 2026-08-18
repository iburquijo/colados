package com.softbenur.colados.ingest;

import com.softbenur.colados.contracts.TagReadBatch;
import com.softbenur.colados.ingest.internal.RawReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La puerta de calidad de la entrada.
 *
 * <p>No es un volcado de MQTT a una tabla: valida, deduplica y persiste antes de que
 * nadie razone sobre el dato.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final RawReadRepository repository;
    private final ApplicationEventPublisher events;

    public IngestService(RawReadRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    public void ingest(TagReadBatch batch) {
        // MQTT QoS 1 es at-least-once: si se pierde el PUBACK, el lector reenvía. Los
        // duplicados de transporte llegan seguro, así que la idempotencia por
        // (readerId, batchSeq) no es opcional (ADR-0008).
        boolean nuevo = repository.markBatchProcessed(
                batch.readerId(), batch.batchSeq(),
                batch.windowStart(), batch.windowEnd(), batch.reads().size());

        if (!nuevo) {
            log.debug("Lote duplicado descartado: {}", batch.idempotencyKey());
            events.publishEvent(new BatchIngested(batch, true));
            return;
        }

        // Un lote vacío es información válida —"he mirado y no había nada"— y por eso se
        // registra como procesado aunque no haya nada que guardar.
        if (!batch.isEmpty()) {
            var reads = batch.reads().stream()
                    .map(r -> new RawRead(
                            batch.readerId(),
                            batch.readerType().name(),
                            batch.batchSeq(),
                            r.epc(),
                            r.rssi(),
                            r.readAt(),
                            batch.traceId()))
                    .toList();
            repository.saveAll(reads);
        }

        events.publishEvent(new BatchIngested(batch, false));
    }
}
