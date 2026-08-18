package com.softbenur.colados.api;

import com.softbenur.colados.ingest.BatchIngested;
import java.time.Instant;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Empuja al navegador lo que va entrando.
 *
 * <p>Ojo con lo que <b>no</b> se manda: las lecturas crudas son cientos por segundo y no
 * tienen sentido en una pantalla. Se manda un resumen por lote. Cuando exista el motor de
 * resolución, por aquí irán los eventos de dominio —que son unos cientos al día— y no esto.
 */
@Component
public class LiveReadsBroadcaster {

    private final SimpMessagingTemplate messaging;

    public LiveReadsBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public record BatchSummary(
            String readerId,
            long batchSeq,
            int readCount,
            boolean duplicate,
            List<String> epcs,
            Instant at) {}

    @EventListener
    public void on(BatchIngested event) {
        var batch = event.batch();
        var epcs = batch.reads().stream()
                .map(r -> r.epc())
                .distinct()
                .toList();

        messaging.convertAndSend("/topic/reads", new BatchSummary(
                batch.readerId(),
                batch.batchSeq(),
                batch.reads().size(),
                event.duplicate(),
                epcs,
                batch.windowStart()));
    }
}
