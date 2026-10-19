package com.bearpi.posture.service;

import com.bearpi.posture.controller.DeviceEventController;
import com.bearpi.posture.controller.DeviceController;
import com.bearpi.posture.entity.PostureEvent;
import com.bearpi.posture.repository.PostureEventRepository;
import com.bearpi.posture.websocket.DeviceWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.bearpi.posture.service.PostureEventIntegrationTest.sample;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PostureEventFailureTest {
    @Test void failedWriteDoesNotTurnHeartbeatsIntoNewEventsAndNextTransitionKeepsPreviousState() {
        var repository = mock(PostureEventRepository.class);
        when(repository.saveAndFlush(any(PostureEvent.class)))
            .thenThrow(new DataAccessResourceFailureException("Database offline"))
            .thenAnswer(call -> call.getArgument(0));
        var service = new DeviceStateService(mock(DeviceWebSocketHandler.class), new PostureEventService(repository));
        service.update(sample("IMPACT", true, "2026-09-28T10:00:00Z", 500));
        service.update(sample("IMPACT", true, "2026-09-28T10:00:01Z", 501));
        service.update(sample("NORMAL", true, "2026-09-28T10:00:02Z", 0));
        var events = org.mockito.ArgumentCaptor.forClass(PostureEvent.class);
        verify(repository, times(2)).saveAndFlush(events.capture());
        assertThat(events.getAllValues().get(0).getPreviousState()).isNull();
        assertThat(events.getAllValues().get(1).getPreviousState()).isEqualTo("IMPACT");
        assertThat(events.getAllValues().get(1).getState()).isEqualTo("NORMAL");
    }

    @Test void databaseFailurePreservesLiveStatusAndBroadcast() throws Exception {
        var repository = mock(PostureEventRepository.class);
        when(repository.saveAndFlush(any(PostureEvent.class)))
                .thenThrow(new DataAccessResourceFailureException("Database offline"));
        var socket = mock(DeviceWebSocketHandler.class);
        var service = new DeviceStateService(socket, new PostureEventService(repository));
        var report = sample("IMPACT", true, "2026-09-28T10:00:00Z", 500);
        assertThatCode(() -> service.update(report)).doesNotThrowAnyException();
        assertThat(service.getLatestStatus()).isSameAs(report);
        verify(socket).broadcast(report);
        MockMvcBuilders.standaloneSetup(new DeviceController(service)).build()
                .perform(get("/api/device/status")).andExpect(status().isOk())
                .andExpect(jsonPath("state").value("IMPACT")).andExpect(jsonPath("x").value(500));
    }

    @Test void transactionCreationFailureAlsoReturns503() throws Exception {
        var service = mock(PostureEventService.class);
        when(service.findLatest(20))
                .thenThrow(new CannotCreateTransactionException("Database connection refused"));
        MockMvcBuilders.standaloneSetup(new DeviceEventController(service)).build()
                .perform(get("/api/device/events")).andExpect(status().isServiceUnavailable());
    }

    @Test void unavailableHistoryReturns503WithoutDatabaseDetails() throws Exception {
        var service = mock(PostureEventService.class);
        when(service.findLatest(20))
                .thenThrow(new DataAccessResourceFailureException("Private connection details"));
        MockMvcBuilders.standaloneSetup(new DeviceEventController(service)).build()
                .perform(get("/api/device/events")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("detail").value("Event history is temporarily unavailable"));
    }
}
