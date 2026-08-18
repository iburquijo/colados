package com.softbenur.colados.simulator.layout;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Carga {@code infra/plant-layout.yaml}. */
@Component
public class LayoutLoader {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PlantLayout load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el layout: " + path, e);
        }
    }

    public PlantLayout parse(InputStream in) {
        try {
            PlantLayout layout = mapper.readValue(in, PlantLayout.class);
            if (layout == null) {
                throw new IllegalStateException("Layout vacío");
            }
            return layout;
        } catch (IOException e) {
            throw new IllegalStateException("Layout malformado", e);
        }
    }
}
