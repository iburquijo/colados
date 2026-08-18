package com.softbenur.colados;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Monolito modular (ADR-0003). Los módulos viven en paquetes con frontera explícita y no
 * se llaman entre sí por método: publican hechos. Esa disciplina la imponen los tests de
 * ArchUnit, no la buena voluntad.
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
