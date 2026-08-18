package com.softbenur.colados.simulator.layout;

/**
 * Un hueco del patio con su posición física y su tag de ubicación.
 *
 * <p>La capacidad es configurable y por defecto 1 (ADR-0013). Subirla no cambia el
 * modelo: solo hace que aparezca ambigüedad al recoger, que se resuelve preguntando
 * al operario en el terminal.
 */
public record Slot(String id, String zoneId, String rowId, double x, double y, int capacity) {

    /**
     * EPC del tag de ubicación empotrado en este hueco.
     *
     * <p>Derivado del id para que el simulador y el backend coincidan sin negociar nada.
     * En una planta real vendría de la tabla {@code location_tag}.
     */
    public String locationTagEpc() {
        return "LOC-" + id;
    }

    public double distanceTo(double px, double py) {
        double dx = x - px;
        double dy = y - py;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
