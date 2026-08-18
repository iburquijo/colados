package com.softbenur.colados.simulator;

import com.softbenur.colados.contracts.ReaderStatus;
import com.softbenur.colados.contracts.ReaderType;
import com.softbenur.colados.contracts.TagRead;
import com.softbenur.colados.contracts.TagReadBatch;
import com.softbenur.colados.simulator.layout.LayoutLoader;
import com.softbenur.colados.simulator.layout.PlantLayout;
import com.softbenur.colados.simulator.layout.Slot;
import com.softbenur.colados.simulator.mqtt.MqttPublisher;
import com.softbenur.colados.simulator.physics.Coil;
import com.softbenur.colados.simulator.physics.Forklift;
import com.softbenur.colados.simulator.physics.NoiseModel;
import com.softbenur.colados.simulator.physics.RfEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bucle del simulador: mueve la carretilla, pregunta al motor RF qué ve y publica el
 * informe de inventario por MQTT.
 *
 * <p>Fase 1: una carretilla que va llevando bobinas de la salida de línea a huecos
 * libres, sin parar. Suficiente para que el bucle completo funcione de punta a punta.
 */
@Component
public class SimulationLoop implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationLoop.class);

    private final SimulatorProperties props;
    private final NoiseModel noise;
    private final LayoutLoader layoutLoader;
    private final ObjectMapper mapper;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "sim-loop");
                t.setDaemon(true);
                return t;
            });

    private MqttPublisher publisher;
    private Forklift forklift;
    private RfEngine rf;
    private List<Slot> slots;
    private Random random;
    private PlantLayout layout;

    private long batchSeq = 0;
    private long readsLastMinute = 0;
    private final Instant startedAt = Instant.now();
    private int coilCounter = 0;
    private int nextFreeSlot = 0;

    public SimulationLoop(SimulatorProperties props, NoiseModel noise,
                          LayoutLoader layoutLoader, ObjectMapper mapper) {
        this.props = props;
        this.noise = noise;
        this.layoutLoader = layoutLoader;
        this.mapper = mapper;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        layout = layoutLoader.load(Path.of(props.getLayoutPath()));
        slots = layout.slots();
        random = new Random(props.getSeed());
        rf = new RfEngine(random, noise);

        PlantLayout.Machine machine = layout.machines().getFirst();
        forklift = new Forklift(machine, 0, 0);

        publisher = new MqttPublisher(
                props.getBrokerUrl(), layout.plantId(), machine.reader().id(), mapper);

        log.info("Patio '{}': {} huecos, capacidad {} — carretilla {}",
                layout.profile(), slots.size(), slots.getFirst().capacity(), machine.id());

        long periodMs = (long) (props.getBatchWindowMs() / props.getSpeedFactor());
        scheduler.scheduleAtFixedRate(this::tick, 0, periodMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    /** Un tick = una ventana de lote. */
    private void tick() {
        try {
            double dtS = props.getBatchWindowMs() / 1000.0;
            if (forklift.isIdle()) {
                assignNextTask();
            }
            forklift.tick(dtS);

            Instant windowStart = Instant.now();
            List<TagRead> reads = rf.readsFor(
                    forklift, slots, windowStart,
                    props.getBatchWindowMs(), props.getReadAttemptsPerWindow());
            readsLastMinute += reads.size();

            // Un lote vacío también se publica: "he mirado y no había nada" es un dato
            // distinto de no haber publicado (ADR-0008).
            var batch = new TagReadBatch(
                    TagReadBatch.SCHEMA,
                    layout.plantId(),
                    forklift.readerId(),
                    ReaderType.MACHINE,
                    ++batchSeq,
                    windowStart,
                    windowStart.plusMillis(props.getBatchWindowMs()),
                    UUID.randomUUID().toString().replace("-", ""),
                    reads);

            publisher.publish(batch);

            // MQTT QoS 1 es at-least-once: el reenvío por PUBACK perdido produce
            // duplicados de verdad. Se simulan aquí para que la ingesta tenga que ser
            // idempotente de verdad, no de mentira.
            if (noise.duplicateBatch(random)) {
                publisher.publish(batch);
            }
        } catch (Exception e) {
            log.error("Fallo en el tick del simulador", e);
        }
    }

    /** Manda la carretilla a por una bobina nueva de la salida de línea. */
    private void assignNextTask() {
        if (nextFreeSlot >= slots.size()) {
            nextFreeSlot = 0;   // el patio se recicla: es una demo, no un ERP
        }
        var coil = new Coil(
                "2026-%06d".formatted(++coilCounter),
                "CAST-2026-001",
                6000 + random.nextInt(6000));
        Slot lineExit = slots.getFirst();
        Slot destination = slots.get(nextFreeSlot++);
        forklift.assign(coil, lineExit, destination);
        log.debug("Tarea: {} de {} a {}", coil.id(), lineExit.id(), destination.id());
    }

    private void heartbeat() {
        publisher.publishStatus(new ReaderStatus(
                ReaderStatus.SCHEMA,
                forklift.readerId(),
                ReaderStatus.State.ONLINE,
                java.time.Duration.between(startedAt, Instant.now()).toSeconds(),
                readsLastMinute,
                Instant.now()));
        readsLastMinute = 0;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        if (publisher != null) {
            publisher.close();
        }
    }
}
