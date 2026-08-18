package com.softbenur.colados.api;

import com.softbenur.colados.ingest.RawRead;
import com.softbenur.colados.ingest.ReadQueries;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Foto inicial para el navegador: al conectarse pide esto y luego sigue por WebSocket. */
@RestController
@RequestMapping("/api/reads")
@CrossOrigin(originPatterns = "*")
public class ReadsController {

    private final ReadQueries reads;

    public ReadsController(ReadQueries reads) {
        this.reads = reads;
    }

    @GetMapping
    public List<RawRead> latest(@RequestParam(defaultValue = "50") int limit) {
        return reads.latest(limit);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("totalReads", reads.totalReads());
    }
}
