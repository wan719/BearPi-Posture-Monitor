package com.bearpi.posture.websocket;

import org.springframework.context.annotation.Configuration;

import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig
        implements WebSocketConfigurer {

    private final DeviceWebSocketHandler handler;

    public WebSocketConfig(
            DeviceWebSocketHandler handler) {

        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry) {

        registry
                .addHandler(
                        handler,
                        "/ws/device"
                )
                /*
                 * V5开发阶段允许Vue开发服务器连接。
                 *
                 * 后续部署时可以收紧来源。
                 */
                .setAllowedOrigins("*");
    }
}