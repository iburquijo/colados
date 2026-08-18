package com.softbenur.colados.simulator.physics;

import com.softbenur.colados.contracts.TagRead;
import com.softbenur.colados.simulator.layout.Slot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Motor RF: decide qué tags vería el lector embarcado en este instante y con qué
 * potencia.
 *
 * <p>Modelo: <i>path loss</i> logarítmico + ruido gaussiano + curva sigmoide de
 * probabilidad de lectura. El multitrayecto no se modela físicamente; sus efectos se
 * reproducen con las perillas de ruido, que dan el mismo comportamiento observable a una
 * fracción del coste.
 */
public class RfEngine {

    /** Potencia de referencia a 1 m, en dBm. */
    private static final double P0_DBM = -40.0;
    /** Exponente de pérdida de propagación. 2,0 es espacio libre; en patio con metal, más. */
    private static final double PATH_LOSS_EXPONENT = 2.2;
    private static final double NOISE_SIGMA_DB = 2.5;
    /** Por debajo de esto el tag no responde: no tiene energía para alimentarse. */
    private static final double SENSITIVITY_DBM = -75.0;

    private final Random random;
    private final NoiseModel noise;

    public RfEngine(Random random, NoiseModel noise) {
        this.random = random;
        this.noise = noise;
    }

    /**
     * Lecturas que produce un lector embarcado durante una ventana.
     *
     * @param intentos cuántas veces intenta leer el lector en la ventana (~4 a 20 Hz)
     */
    public List<TagRead> readsFor(Forklift forklift, List<Slot> slots, Instant windowStart,
                                  long windowMs, int intentos) {
        var reads = new ArrayList<TagRead>();

        for (int i = 0; i < intentos; i++) {
            Instant t = windowStart.plusMillis((long) ((i + 0.5) / intentos * windowMs));

            // 1. La bobina que lleva encima: distancia fija y corta, señal fuerte y estable.
            Coil load = forklift.load();
            if (load != null) {
                double rssi = rssiAt(1.0);
                if (!noise.missed(random) && random.nextDouble() < readProbability(rssi)) {
                    reads.add(new TagRead(load.tagEpc(), round(rssi), t));
                }
            }

            // 2. Los tags de ubicación al alcance. El lector no sabe que son de ubicación.
            for (Slot slot : slots) {
                double d = slot.distanceTo(forklift.x(), forklift.y());
                if (d > forklift.rangeM()) {
                    continue;
                }
                if (noise.locationTagUnreadable(random)) {
                    continue;
                }
                double rssi = rssiAt(d);
                if (rssi < SENSITIVITY_DBM) {
                    continue;
                }
                if (!noise.missed(random) && random.nextDouble() < readProbability(rssi)) {
                    reads.add(new TagRead(slot.locationTagEpc(), round(rssi), t));
                }
            }
        }
        return reads;
    }

    /** RSSI esperado a una distancia dada, con ruido gaussiano. */
    private double rssiAt(double distanceM) {
        double d = Math.max(distanceM, 0.3);
        return P0_DBM - 10 * PATH_LOSS_EXPONENT * Math.log10(d) + random.nextGaussian() * NOISE_SIGMA_DB;
    }

    /**
     * Probabilidad de que un intento de lectura tenga éxito, en función del RSSI.
     *
     * <p>Sigmoide centrada en −62 dBm: cerca lee casi siempre, en el borde de cobertura
     * lee de forma intermitente. Esa intermitencia del borde es la que genera la
     * ambigüedad que el backend tiene que resolver.
     */
    private double readProbability(double rssiDbm) {
        return 1.0 / (1.0 + Math.exp(-(rssiDbm + 62.0) / 3.0));
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
