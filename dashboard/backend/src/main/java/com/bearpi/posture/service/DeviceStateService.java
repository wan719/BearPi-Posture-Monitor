package com.bearpi.posture.service;

import com.bearpi.posture.model.PostureStatus;
import com.bearpi.posture.websocket.DeviceWebSocketHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class DeviceStateService {

    private static final Logger log =
            LoggerFactory.getLogger(DeviceStateService.class);

    private final DeviceWebSocketHandler webSocketHandler;

    private volatile PostureStatus latestStatus;


    public DeviceStateService(
            DeviceWebSocketHandler webSocketHandler) {

        this.webSocketHandler = webSocketHandler;
    }


    public void update(PostureStatus status) {

        /*
         * MQTT数据抵达Spring Boot的时间。
         */
        status.setReceivedAt(
                Instant.now()
        );


        /*
         * 保存最新设备状态。
         */
        latestStatus =
                status;


        log.info(
                "\n" +
                "================ DEVICE STATUS ================\n" +
                "armed       = {}\n" +
                "state       = {}\n" +
                "x           = {}\n" +
                "y           = {}\n" +
                "z           = {}\n" +
                "base        = {}\n" +
                "change      = {}\n" +
                "temperature = {} C\n" +
                "receivedAt  = {}\n" +
                "===============================================",

                status.isArmed(),
                status.getState(),

                status.getX(),
                status.getY(),
                status.getZ(),

                status.getBase(),
                status.getChange(),

                status.getTemperature(),

                status.getReceivedAt()
        );


        /*
         * V5.2：
         * MQTT收到新状态以后，
         * 立即推给所有WebSocket客户端。
         */
        webSocketHandler.broadcast(
                status
        );
    }


    public PostureStatus getLatestStatus() {

        return latestStatus;
    }
}