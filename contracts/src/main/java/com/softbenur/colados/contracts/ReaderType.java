package com.softbenur.colados.contracts;

/** Tipos de lector (ADR-0010): el lector viaja con la máquina, no cubre el patio. */
public enum ReaderType {
    /** Embarcado en una carretilla o pórtico. Ve la bobina que lleva y los tags de ubicación al pasar. */
    MACHINE,
    /** Punto de paso fijo: salida de línea, báscula, puerta de expedición. */
    GATE,
    /** Lector de mano del operario, para el inventario periódico. */
    HANDHELD
}
