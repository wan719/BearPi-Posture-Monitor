package com.bearpi.posture.mqtt;

import com.bearpi.posture.config.MqttProperties;
import com.bearpi.posture.model.PostureStatus;
import com.bearpi.posture.service.DeviceStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MqttSubscriber implements MqttCallbackExtended {

    private static final Logger log =
            LoggerFactory.getLogger(MqttSubscriber.class);

    private final MqttProperties properties;
    private final ObjectMapper objectMapper;
    private final DeviceStateService deviceStateService;

    private MqttClient client;

    public MqttSubscriber(
            MqttProperties properties,
            ObjectMapper objectMapper,
            DeviceStateService deviceStateService) {

        this.properties = properties;
        this.objectMapper = objectMapper;
        this.deviceStateService = deviceStateService;
    }

    @PostConstruct
    public void start() {

        try {
            client = new MqttClient(
                    properties.getBroker(),
                    properties.getClientId(),
                    new MemoryPersistence()
            );

            client.setCallback(this);

            MqttConnectOptions options =
                    new MqttConnectOptions();

            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            log.info("[MQTT] Connecting to {}", properties.getBroker());

            client.connect(options);

            subscribe();

            log.info("[MQTT] Connected successfully");

        } catch (Exception e) {
            log.error("[MQTT] Initial connection failed", e);
        }
    }

    private void subscribe() throws MqttException {

        client.subscribe(
                properties.getTopic(),
                properties.getQos()
        );

        log.info(
                "[MQTT] Subscribed: {} QoS={}",
                properties.getTopic(),
                properties.getQos()
        );
    }

    @Override
    public void connectComplete(
            boolean reconnect,
            String serverURI) {

        log.info(
                "[MQTT] connectComplete reconnect={} server={}",
                reconnect,
                serverURI
        );

        if (reconnect) {
            try {
                subscribe();
            } catch (MqttException e) {
                log.error("[MQTT] Re-subscribe failed", e);
            }
        }
    }

    @Override
    public void connectionLost(Throwable cause) {

        log.warn(
                "[MQTT] Connection lost: {}",
                cause == null ? "unknown" : cause.getMessage()
        );
    }

    @Override
    public void messageArrived(
            String topic,
            MqttMessage message) {

        String payload =
                new String(
                        message.getPayload(),
                        StandardCharsets.UTF_8
                );

        log.info(
                "[MQTT] Message received topic={} payload={}",
                topic,
                payload
        );

        try {
            PostureStatus status =
                    objectMapper.readValue(
                            payload,
                            PostureStatus.class
                    );

            deviceStateService.update(status);

        } catch (Exception e) {
            log.error(
                    "[MQTT] Invalid JSON: {}",
                    payload,
                    e
            );
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @PreDestroy
    public void stop() {

        try {
            if (client != null) {

                if (client.isConnected()) {
                    client.disconnect();
                }

                client.close();
            }

        } catch (Exception e) {
            log.warn("[MQTT] Close failed", e);
        }
    }
}