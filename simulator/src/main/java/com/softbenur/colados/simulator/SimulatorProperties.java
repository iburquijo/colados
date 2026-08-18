package com.softbenur.colados.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "colados.sim")
public class SimulatorProperties {

    /** URL del broker MQTT. */
    private String brokerUrl = "tcp://localhost:1883";

    /** Ruta del layout compartido con el backend. */
    private String layoutPath = "infra/plant-layout.yaml";

    /** Ventana del informe de inventario, en ms (ADR-0008). */
    private long batchWindowMs = 200;

    /** Intentos de lectura por ventana. A 200 ms y 4 intentos salen ~20 lecturas/s por tag. */
    private int readAttemptsPerWindow = 4;

    /**
     * Semilla fija: con la misma semilla y los mismos parámetros el simulador produce
     * exactamente la misma traza. Sin esto no hay tests reproducibles.
     */
    private long seed = 42;

    /** Factor de aceleración del tiempo simulado. */
    private double speedFactor = 1.0;

    public String getBrokerUrl() { return brokerUrl; }
    public void setBrokerUrl(String v) { this.brokerUrl = v; }

    public String getLayoutPath() { return layoutPath; }
    public void setLayoutPath(String v) { this.layoutPath = v; }

    public long getBatchWindowMs() { return batchWindowMs; }
    public void setBatchWindowMs(long v) { this.batchWindowMs = v; }

    public int getReadAttemptsPerWindow() { return readAttemptsPerWindow; }
    public void setReadAttemptsPerWindow(int v) { this.readAttemptsPerWindow = v; }

    public long getSeed() { return seed; }
    public void setSeed(long v) { this.seed = v; }

    public double getSpeedFactor() { return speedFactor; }
    public void setSpeedFactor(double v) { this.speedFactor = v; }
}
