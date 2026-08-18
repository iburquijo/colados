package com.softbenur.colados.simulator.mqtt;

import com.softbenur.colados.contracts.ReaderStatus;
import com.softbenur.colados.contracts.TagReadBatch;
import com.softbenur.colados.contracts.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cliente MQTT del lector simulado.
 *
 * <p>Usa exactamente el mismo cliente (Eclipse Paho), el mismo topic y el mismo payload
 * que usaría un lector físico. Del broker en adelante, el sistema no puede distinguir si
 * las lecturas vienen de aquí o de un ESP32 (ADR-0006).
 */
public class MqttPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    private final IMqttClient client;
    private final ObjectMapper mapper;
    private final String plantId;
    private final String readerId;

    public MqttPublisher(String brokerUrl, String plantId, String readerId, ObjectMapper mapper)
            throws MqttException {
        this.plantId = plantId;
        this.readerId = readerId;
        this.mapper = mapper;
        this.client = new MqttClient(brokerUrl, "sim-" + readerId, new MemoryPersistence());

        var options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);

        // Last Will and Testament: si este lector pierde la conexión, el broker publica
        // OFFLINE por él. Así una caída es un evento del sistema y no un silencio que
        // nadie interpreta (ADR-0001) — exactamente lo que le faltaba al montaje de 2021.
        options.setWill(
                Topics.status(plantId, readerId),
                payload(ReaderStatus.lastWill(readerId)),
                1,
                true);

        client.connect(options);
        log.info("Lector {} conectado a {}", readerId, brokerUrl);
    }

    /** Publica un informe de inventario. QoS 1: at-least-once, habrá duplicados. */
    public void publish(TagReadBatch batch) {
        try {
            var message = new MqttMessage(payload(batch));
            message.setQos(1);
            client.publish(Topics.reads(plantId, batch.readerId()), message);
        } catch (MqttException e) {
            log.warn("No se pudo publicar el lote {}: {}", batch.batchSeq(), e.getMessage());
        }
    }

    /** Latido, retenido para que quien se suscriba conozca el estado sin esperar. */
    public void publishStatus(ReaderStatus status) {
        try {
            var message = new MqttMessage(payload(status));
            message.setQos(1);
            message.setRetained(true);
            client.publish(Topics.status(plantId, readerId), message);
        } catch (MqttException e) {
            log.warn("No se pudo publicar el estado: {}", e.getMessage());
        }
    }

    private byte[] payload(Object value) {
        try {
            return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo serializar el payload", e);
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                // Cierre limpio: se publica OFFLINE a mano, porque el LWT solo salta
                // cuando la conexión se corta de forma anómala.
                publishStatus(new ReaderStatus(
                        ReaderStatus.SCHEMA, readerId, ReaderStatus.State.OFFLINE,
                        0, 0, java.time.Instant.now()));
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            log.warn("Cierre sucio del cliente MQTT: {}", e.getMessage());
        }
    }
}
