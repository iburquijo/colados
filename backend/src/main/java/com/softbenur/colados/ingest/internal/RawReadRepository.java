package com.softbenur.colados.ingest.internal;

import java.sql.Timestamp;
import java.util.List;
import com.softbenur.colados.ingest.RawRead;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RawReadRepository {

    private final JdbcTemplate jdbc;

    public RawReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveAll(List<RawRead> reads) {
        if (reads.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO raw_read (reader_id, reader_type, batch_seq, epc, rssi, read_at, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                reads, reads.size(),
                (ps, r) -> {
                    ps.setString(1, r.readerId());
                    ps.setString(2, r.readerType());
                    ps.setLong(3, r.batchSeq());
                    ps.setString(4, r.epc());
                    ps.setDouble(5, r.rssi());
                    ps.setTimestamp(6, Timestamp.from(r.readAt()));
                    ps.setString(7, r.traceId());
                });
    }

    /**
     * Registra el lote como procesado.
     *
     * @return true si es nuevo; false si ya estaba, es decir, si es un duplicado de
     *         transporte de los que MQTT QoS 1 garantiza que llegarán
     */
    public boolean markBatchProcessed(String readerId, long batchSeq,
                                      java.time.Instant windowStart,
                                      java.time.Instant windowEnd, int readCount) {
        int inserted = jdbc.update("""
                INSERT INTO processed_batch (reader_id, batch_seq, window_start, window_end, read_count)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (reader_id, batch_seq) DO NOTHING
                """,
                readerId, batchSeq, Timestamp.from(windowStart), Timestamp.from(windowEnd), readCount);
        return inserted > 0;
    }

    /** Últimas lecturas, para la vista en vivo de la fase 1. */
    public List<RawRead> latest(int limit) {
        return jdbc.query("""
                SELECT reader_id, reader_type, batch_seq, epc, rssi, read_at, trace_id
                FROM raw_read
                ORDER BY read_at DESC
                LIMIT ?
                """,
                (rs, i) -> new RawRead(
                        rs.getString("reader_id"),
                        rs.getString("reader_type"),
                        rs.getLong("batch_seq"),
                        rs.getString("epc"),
                        rs.getDouble("rssi"),
                        rs.getTimestamp("read_at").toInstant(),
                        rs.getString("trace_id")),
                limit);
    }

    public long countReads() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM raw_read", Long.class);
        return n == null ? 0 : n;
    }
}
