package com.softbenur.colados.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El {@link ObjectMapper} con el que se serializa lo que sale por el cable.
 *
 * <p>Hay que declararlo a mano: Spring Boot solo publica el bean cuando encuentra
 * {@code Jackson2ObjectMapperBuilder}, que vive en {@code spring-web}, y el simulador no
 * es una aplicación web ({@code web-application-type: none}). Sin esto el simulador ni
 * arranca.
 *
 * <p>La configuración no es libre: tiene que producir el mismo JSON que espera la ingesta
 * y que fija el test de contrato. En concreto, los instantes van en ISO-8601 y no como
 * número de segundos, porque el payload de un lector es texto que un humano tiene que
 * poder leer con {@code mosquitto_sub} cuando algo va mal.
 */
@Configuration
public class JsonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
