package com.softbenur.colados.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TagReadBatchTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static TagReadBatch batch(List<TagRead> reads) {
        Instant t = Instant.parse("2026-08-17T09:14:23Z");
        return new TagReadBatch(
                TagReadBatch.SCHEMA, "PLANT-01", "RDR-MACH-CTR01", ReaderType.MACHINE,
                918273, t, t.plusMillis(200), "trace", reads);
    }

    @Test
    @DisplayName("un lote vacío es válido: 'he mirado y no había nada' es información")
    void loteVacioEsValido() {
        var b = batch(List.of());
        assertTrue(b.isEmpty());
        assertEquals(0, b.reads().size());
    }

    @Test
    @DisplayName("la clave de idempotencia es (readerId, batchSeq)")
    void claveDeIdempotencia() {
        assertEquals("RDR-MACH-CTR01:918273", batch(List.of()).idempotencyKey());
    }

    @Test
    @DisplayName("rechaza una ventana con el fin antes del principio")
    void ventanaInvalida() {
        Instant t = Instant.parse("2026-08-17T09:14:23Z");
        assertThrows(IllegalArgumentException.class, () -> new TagReadBatch(
                TagReadBatch.SCHEMA, "PLANT-01", "RDR", ReaderType.MACHINE,
                1, t, t.minusSeconds(1), "trace", List.of()));
    }

    @Test
    @DisplayName("una lectura sin EPC no existe")
    void lecturaSinEpc() {
        Instant t = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new TagRead("", -50, t));
    }

    @Test
    @DisplayName("el lote sobrevive a una ida y vuelta por JSON")
    void serializaYDeserializa() throws Exception {
        var original = batch(List.of(
                new TagRead("COIL-2026-000001", -41.2, Instant.parse("2026-08-17T09:14:23.184Z")),
                new TagRead("LOC-A1-03", -63.4, Instant.parse("2026-08-17T09:14:23.043Z"))));

        String json = mapper.writeValueAsString(original);
        var vuelta = mapper.readValue(json, TagReadBatch.class);

        assertEquals(original, vuelta);
        // Lo que NO debe aparecer nunca en el payload de un lector (ADR-0006)
        assertTrue(!json.contains("coilId") && !json.contains("slotId") && !json.contains("zoneId"),
                "una lectura cruda no puede llevar identificadores de dominio");
    }
}
