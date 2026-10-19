package com.bearpi.posture.service;

import com.bearpi.posture.model.PostureStatus;
import com.bearpi.posture.entity.PostureEvent;
import com.bearpi.posture.websocket.DeviceWebSocketHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
public class DeviceStateService {

    private static final Logger log =
            LoggerFactory.getLogger(DeviceStateService.class);

    private final DeviceWebSocketHandler webSocketHandler;
    private final PostureEventService eventService;

    private volatile PostureStatus latestStatus;


    public DeviceStateService(
            DeviceWebSocketHandler webSocketHandler,
            PostureEventService eventService) {

        this.webSocketHandler = webSocketHandler;
        this.eventService = eventService;
    }


    public synchronized void update(PostureStatus status) {

        /*
         * MQTT数据抵达Spring Boot的时间。
         */
        status.setReceivedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        PostureStatus previous = latestStatus;
        boolean changed = previous == null
                || previous.isArmed() != status.isArmed()
                || !Objects.equals(previous.getState(), status.getState());
        PostureEvent event = changed
                ? new PostureEvent(previous == null ? null : previous.getState(), status)
                : null;


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

        // Every report still updates REST/WebSocket, but only transitions enter the DB queue.
        if (event == null) return;
        try {
            eventService.recordAsync(event);
        } catch (TaskRejectedException e) {
            log.error("[Events] Persistence queue full or stopping: state={} receivedAt={}; event not queued",
                    status.getState(), status.getReceivedAt(), e);
        }
    }


    public PostureStatus getLatestStatus() {

        return latestStatus;
    }
}