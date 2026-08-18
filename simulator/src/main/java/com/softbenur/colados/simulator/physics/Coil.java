package com.softbenur.colados.simulator.physics;

/** Una bobina en el mundo simulado. En planta se le llama también "lote". */
public record Coil(String id, String castId, int weightKg) {

    /** EPC del tag pegado a su etiqueta de salida. */
    public String tagEpc() {
        return "COIL-" + id;
    }
}
