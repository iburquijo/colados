-- Fase 1: solo las lecturas crudas.
--
-- `raw_read` es el registro de lo que dijeron los lectores, sin interpretar. Se escribe
-- ANTES de razonar sobre ella: si el motor de resolución falla, el dato está a salvo y
-- se puede reprocesar (ADR-0004).
--
-- Particionada por día para que el borrado sea DROP PARTITION y no un DELETE masivo
-- que fragmenta la tabla. Retención: 7 días (ADR-0009).

CREATE TABLE raw_read (
    id           BIGSERIAL,
    reader_id    TEXT        NOT NULL,
    reader_type  TEXT        NOT NULL,
    batch_seq    BIGINT      NOT NULL,
    epc          TEXT        NOT NULL,
    rssi         REAL        NOT NULL,
    read_at      TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    trace_id     TEXT,
    PRIMARY KEY (id, read_at)
) PARTITION BY RANGE (read_at);

-- Partición por defecto para que la tabla sea usable desde el primer día. Las
-- particiones diarias y el trabajo de retención (DROP PARTITION a los 7 días) llegan
-- cuando el volumen lo pida; hoy no lo pide.
CREATE TABLE raw_read_default PARTITION OF raw_read DEFAULT;

CREATE INDEX idx_raw_read_reader_time ON raw_read (reader_id, read_at DESC);
CREATE INDEX idx_raw_read_epc         ON raw_read (epc, read_at DESC);

-- Lotes ya procesados, para descartar los duplicados que MQTT QoS 1 garantiza que
-- llegarán. La clave es (reader_id, batch_seq): ADR-0008.
CREATE TABLE processed_batch (
    reader_id    TEXT        NOT NULL,
    batch_seq    BIGINT      NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    read_count   INT         NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (reader_id, batch_seq)
);

-- Salud de los lectores. El estado OFFLINE puede venir del propio lector o del Last
-- Will publicado por el broker cuando se corta la conexión (ADR-0001).
CREATE TABLE reader_health (
    reader_id         TEXT PRIMARY KEY,
    status            TEXT        NOT NULL,
    uptime_s          BIGINT      NOT NULL DEFAULT 0,
    reads_last_minute BIGINT      NOT NULL DEFAULT 0,
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
