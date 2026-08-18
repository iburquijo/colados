package com.softbenur.colados.simulator.physics;

import java.util.Random;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las perillas de ruido. Cada una corresponde a un modo de fallo real del RFID, y son la
 * parte más valiosa del simulador: sin ellas el problema de inferencia no existe.
 *
 * <p>En la fase 1 solo están activas las dos primeras; el resto llegan con el motor de
 * resolución.
 */
@ConfigurationProperties(prefix = "colados.sim.noise")
public class NoiseModel {

    /** Tag orientado hacia el metal, agua, apantallamiento. */
    private double missRate = 0.10;

    /** Tag de ubicación sucio, pisado o tapado por otra bobina. */
    private double locationTagUnreadableRate = 0.05;

    /** El transporte duplica el lote (MQTT QoS 1 es at-least-once). */
    private double duplicateRate = 0.05;

    public boolean missed(Random r) {
        return r.nextDouble() < missRate;
    }

    public boolean locationTagUnreadable(Random r) {
        return r.nextDouble() < locationTagUnreadableRate;
    }

    public boolean duplicateBatch(Random r) {
        return r.nextDouble() < duplicateRate;
    }

    public double getMissRate() { return missRate; }
    public void setMissRate(double v) { this.missRate = v; }

    public double getLocationTagUnreadableRate() { return locationTagUnreadableRate; }
    public void setLocationTagUnreadableRate(double v) { this.locationTagUnreadableRate = v; }

    public double getDuplicateRate() { return duplicateRate; }
    public void setDuplicateRate(double v) { this.duplicateRate = v; }
}
