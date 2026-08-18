package com.softbenur.colados.ingest.internal;

import com.softbenur.colados.contracts.TagReadBatch;
import com.softbenur.colados.ingest.IngestService;
import com.softbenur.colados.contracts.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

/**
 * Suscripción MQTT.
 *
 * <p>El backend abre su propia conexión contra el broker y se suscribe con comodín a
 * todos los lectores de la planta. A partir de ahí <b>el broker le empuja</b> cada
 * mensaje: no hay polling ni intervalo, que es exactamente lo que le faltaba al montaje
 * de 2021 contra ThingSpeak.
 */
@Configuration
public class MqttIngestConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestConfig.class);

    @Bean
    public MqttPahoClientFactory mqttClientFactory(@Value("${colados.mqtt.broker-url}") String brokerUrl) {
        var options = new MqttConnectOptions();
        options.setServerURIs(new String[] {brokerUrl});
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        var factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    public MessageChannel mqttInboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageProducer mqttInbound(MqttPahoClientFactory factory,
                                       MessageChannel mqttInboundChannel,
                                       @Value("${colados.plant-id}") String plantId,
                                       @Value("${colados.mqtt.client-id}") String clientId) {
        // Un solo SUBSCRIBE con comodín cubre todos los lectores de la planta.
        var adapter = new MqttPahoMessageDrivenChannelAdapter(
                clientId, factory, Topics.allReads(plantId));
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInboundChannel);
        adapter.setCompletionTimeout(5000);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInboundChannel")
    public MessageHandler mqttMessageHandler(IngestService ingest, ObjectMapper mapper) {
        return message -> {
            try {
                var batch = mapper.readValue(message.getPayload().toString(), TagReadBatch.class);
                ingest.ingest(batch);
            } catch (Exception e) {
                // Un mensaje malformado no puede tumbar al consumidor. En la fase 3 esto
                // irá a una cola de rechazos con el motivo, no solo al log.
                log.warn("Lote descartado por malformado: {}", e.getMessage());
            }
        };
    }
}
