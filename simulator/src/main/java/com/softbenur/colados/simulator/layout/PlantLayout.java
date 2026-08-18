package com.softbenur.colados.simulator.layout;

import java.util.List;

/**
 * Topología de la planta leída de {@code infra/plant-layout.yaml}.
 *
 * <p>Un único fichero compartido con el backend: el simulador lo usa para la geometría
 * física y el backend para el modelo lógico.
 */
public record PlantLayout(
        String plantId,
        String profile,
        Geometry geometry,
        List<Zone> zones,
        List<Machine> machines,
        List<Gate> gates,
        Production production) {

    public record Geometry(double slotSpacingM, double rowSpacingM) {}

    public record Zone(String id, String name, boolean covered, List<Row> rows) {}

    public record Row(String id, int slots, int slotCapacity) {}

    public record Machine(String id, String name, String type, double speedMs, Reader reader) {}

    public record Reader(String id, String type, double rangeM, double loadDistanceM) {}

    public record Gate(String id, String name, List<Double> positionM, Reader reader) {}

    public record Production(
            List<Integer> castIntervalMinutes,
            List<Integer> coilsPerCast,
            List<Integer> coilWeightKg) {}

    /**
     * Todos los huecos del patio, con su posición ya calculada.
     *
     * <p>Las calles se numeran de forma consecutiva a lo largo de todas las zonas, así
     * que cada una ocupa su propia banda en el eje Y.
     */
    public List<Slot> slots() {
        var result = new java.util.ArrayList<Slot>();
        int rowIndex = 0;
        for (Zone zone : zones) {
            for (Row row : zone.rows()) {
                for (int i = 0; i < row.slots(); i++) {
                    result.add(new Slot(
                            "%s-%02d".formatted(row.id(), i + 1),
                            zone.id(),
                            row.id(),
                            i * geometry.slotSpacingM(),
                            rowIndex * geometry.rowSpacingM(),
                            row.slotCapacity()));
                }
                rowIndex++;
            }
        }
        return List.copyOf(result);
    }
}
