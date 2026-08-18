package com.softbenur.colados.simulator.physics;

import com.softbenur.colados.simulator.layout.PlantLayout;
import com.softbenur.colados.simulator.layout.Slot;

/**
 * Carretilla con lector embarcado (ADR-0010).
 *
 * <p>Ciclo: {@code INACTIVA → hacia origen → recoger → hacia destino → depositar}.
 * Durante los desplazamientos <b>atraviesa calles que no son su destino</b>, y su lector
 * va viendo los tags de ubicación por los que pasa. Distinguir esos tags de paso del tag
 * del hueco de destino es justamente el problema que el backend debe resolver.
 */
public class Forklift {

    public enum Phase { IDLE, TO_PICKUP, PICKING, TO_DROP, DROPPING }

    private final PlantLayout.Machine spec;
    private double x;
    private double y;
    private Phase phase = Phase.IDLE;
    private Coil load;
    private Slot target;
    private double phaseTimerS;

    public Forklift(PlantLayout.Machine spec, double startX, double startY) {
        this.spec = spec;
        this.x = startX;
        this.y = startY;
    }

    public String readerId() {
        return spec.reader().id();
    }

    public double rangeM() {
        return spec.reader().rangeM();
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public Coil load() {
        return load;
    }

    public Phase phase() {
        return phase;
    }

    /** Manda la carretilla a recoger una bobina de un hueco y llevarla a otro. */
    public void assign(Coil coil, Slot from, Slot to) {
        this.load = null;
        this.pendingCoil = coil;
        this.origin = from;
        this.target = to;
        this.phase = Phase.TO_PICKUP;
    }

    private Coil pendingCoil;
    private Slot origin;

    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    /** Avanza la simulación {@code dtS} segundos. */
    public void tick(double dtS) {
        switch (phase) {
            case IDLE -> {}
            case TO_PICKUP -> {
                if (moveTowards(origin.x(), origin.y(), dtS)) {
                    phase = Phase.PICKING;
                    phaseTimerS = 4.0;   // maniobra de recogida
                }
            }
            case PICKING -> {
                phaseTimerS -= dtS;
                if (phaseTimerS <= 0) {
                    load = pendingCoil;
                    phase = Phase.TO_DROP;
                }
            }
            case TO_DROP -> {
                if (moveTowards(target.x(), target.y(), dtS)) {
                    phase = Phase.DROPPING;
                    phaseTimerS = 6.0;   // maniobra de depósito: se queda un momento
                }
            }
            case DROPPING -> {
                phaseTimerS -= dtS;
                // La bobina se suelta a mitad de la maniobra: a partir de ahí el lector
                // deja de verla, y esa desaparición es la señal del depósito.
                if (phaseTimerS <= 3.0) {
                    load = null;
                }
                if (phaseTimerS <= 0) {
                    phase = Phase.IDLE;
                }
            }
        }
    }

    /** @return true si ya ha llegado */
    private boolean moveTowards(double tx, double ty, double dtS) {
        double dx = tx - x;
        double dy = ty - y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double step = spec.speedMs() * dtS;
        if (dist <= step) {
            x = tx;
            y = ty;
            return true;
        }
        x += dx / dist * step;
        y += dy / dist * step;
        return false;
    }
}
