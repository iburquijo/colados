package com.softbenur.colados.contracts;

/** Topics MQTT. Única definición: simulador y backend los toman de aquí. */
public final class Topics {

    private Topics() {}

    public static String reads(String plantId, String readerId) {
        return "colados/%s/reader/%s/reads".formatted(plantId, readerId);
    }

    public static String status(String plantId, String readerId) {
        return "colados/%s/reader/%s/status".formatted(plantId, readerId);
    }

    /** Comodín de un nivel: todas las lecturas de todos los lectores de una planta. */
    public static String allReads(String plantId) {
        return "colados/%s/reader/+/reads".formatted(plantId);
    }

    public static String allStatus(String plantId) {
        return "colados/%s/reader/+/status".formatted(plantId);
    }
}
