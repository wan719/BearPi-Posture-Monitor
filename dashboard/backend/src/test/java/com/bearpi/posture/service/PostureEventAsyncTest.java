package com.bearpi.posture.service;

import com.bearpi.posture.config.EventPersistenceConfig;
import com.bearpi.posture.entity.PostureEvent;
import com.bearpi.posture.repository.PostureEventRepository;
import com.bearpi.posture.websocket.DeviceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import static com.bearpi.posture.service.PostureEventIntegrationTest.sample;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostureEventAsyncTest {
    @Test void blockedWriteDoesNotDelayTheNextLiveReport() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repository = mock(PostureEventRepository.class);
        when(repository.saveAndFlush(any(PostureEvent.class))).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test write timed out");
            return call.getArgument(0);
        });
        var socket = mock(DeviceWebSocketHandler.class);
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(PostureEventRepository.class, () -> repository);
        context.registerBean(DeviceWebSocketHandler.class, () -> socket);
        context.register(EventPersistenceConfig.class, PostureEventService.class, DeviceStateService.class);
        context.refresh();
        var producer = Executors.newSingleThreadExecutor();
        try {
            var service = context.getBean(DeviceStateService.class);
            var first = sample("NORMAL", true, "2026-09-28T10:00:00Z", 1);
            var second = sample("IMPACT", true, "2026-09-28T10:00:01Z", 2);
            var reports = producer.submit(() -> { service.update(first); service.update(second); });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            reports.get(1, TimeUnit.SECONDS);
            assertThat(service.getLatestStatus()).isSameAs(second);
            verify(socket).broadcast(second);
        } finally {
            release.countDown();
            producer.shutdownNow();
            context.close();
        }
    }
}
