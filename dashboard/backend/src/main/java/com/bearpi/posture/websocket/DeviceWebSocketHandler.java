package com.bearpi.posture.websocket;

import com.bearpi.posture.model.PostureStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class DeviceWebSocketHandler
        extends TextWebSocketHandler {

    private static final Logger log =
            LoggerFactory.getLogger(DeviceWebSocketHandler.class);

    /*
     * 保存所有当前连接的浏览器。
     *
     * CopyOnWriteArraySet：
     * 多线程环境下可以安全遍历和增删。
     */
    private final Set<WebSocketSession> sessions =
            new CopyOnWriteArraySet<>();

    private final ObjectMapper objectMapper;

    public DeviceWebSocketHandler(
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;
    }


    /*
     * 浏览器连接成功。
     */
    @Override
    public void afterConnectionEstablished(
            WebSocketSession session) {

        sessions.add(session);

        log.info(
                "[WebSocket] Client connected: {}",
                session.getId()
        );
    }


    /*
     * 浏览器断开连接。
     */
    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status) {

        sessions.remove(session);

        log.info(
                "[WebSocket] Client disconnected: {}",
                session.getId()
        );
    }


    /*
     * WebSocket发生异常。
     */
    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception) {

        sessions.remove(session);

        log.warn(
                "[WebSocket] Transport error session={}: {}",
                session.getId(),
                exception.getMessage()
        );
    }


    /*
     * 向所有浏览器广播最新设备状态。
     */
    public void broadcast(PostureStatus status) {

        try {

            String json =
                    objectMapper.writeValueAsString(status);

            TextMessage message =
                    new TextMessage(json);


            for (WebSocketSession session : sessions) {

                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }

                try {

                    session.sendMessage(message);

                } catch (Exception e) {

                    log.warn(
                            "[WebSocket] Send failed session={}: {}",
                            session.getId(),
                            e.getMessage()
                    );

                    sessions.remove(session);
                }
            }


            if (!sessions.isEmpty()) {

                log.info(
                        "[WebSocket] Broadcast to {} client(s)",
                        sessions.size()
                );
            }

        } catch (Exception e) {

            log.error(
                    "[WebSocket] JSON serialization failed",
                    e
            );
        }
    }
}