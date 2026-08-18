package com.softbenur.colados.ingest;

import com.softbenur.colados.ingest.internal.RawReadRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API pública de consulta del módulo {@code ingest}.
 *
 * <p>Existe para que nadie de fuera toque el repositorio. Podría parecer una capa de
 * paja —hoy solo delega— pero es la frontera: el día que las lecturas cambien de sitio o
 * el módulo se extraiga, esto es lo único que hay que respetar. Lo garantiza un test de
 * ArchUnit, no la buena voluntad (ADR-0003).
 */
@Service
public class ReadQueries {

    private final RawReadRepository repository;

    public ReadQueries(RawReadRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<RawRead> latest(int limit) {
        return repository.latest(Math.clamp(limit, 1, 500));
    }

    @Transactional(readOnly = true)
    public long totalReads() {
        return repository.countReads();
    }
}
