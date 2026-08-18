package com.softbenur.colados.simulator;

import com.softbenur.colados.simulator.physics.NoiseModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Planta simulada. Proceso independiente que <b>solo habla MQTT</b> y publica lecturas
 * crudas de tag: nunca eventos de dominio, nunca {@code coilId} ni {@code slotId}
 * (ADR-0006).
 */
@SpringBootApplication
@EnableConfigurationProperties({SimulatorProperties.class, NoiseModel.class})
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
