package com.bearpi.posture.service;

import com.bearpi.posture.controller.DeviceEventController;
import com.bearpi.posture.model.PostureStatus;
import com.bearpi.posture.repository.PostureEventRepository;
import com.bearpi.posture.websocket.DeviceWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:events;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${TEST_DB_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${TEST_DB_USERNAME:sa}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=${TEST_SQL_INIT:always}",
    "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class PostureEventIntegrationTest {
    @Autowired PostureEventRepository repository;
    @Autowired jakarta.persistence.EntityManager entityManager;
    DeviceStateService deviceService;
    MockMvc mvc;

    @BeforeEach void setup() {
        var service = new PostureEventService(repository);
        deviceService = new DeviceStateService(mock(DeviceWebSocketHandler.class), service);
        mvc = MockMvcBuilders.standaloneSetup(new DeviceEventController(service)).build();
    }

    static PostureStatus sample(String state, boolean armed, String time, int x) {
        PostureStatus value = new PostureStatus();
        value.setState(state); value.setArmed(armed); value.setReceivedAt(Instant.parse(time));
        value.setX(x); value.setY(-20); value.setZ(1500);
        value.setBase(3); value.setChange(10); value.setTemperature(26);
        return value;
    }

    @Test void savesPreviousStateAndBackendReceiptTimeButNotTenIdenticalHeartbeats() throws Exception {
        var before = Instant.now().minusSeconds(1);
        deviceService.update(sample("NORMAL", true, "2020-01-01T00:00:00Z", 1));
        deviceService.update(sample("MOVING", true, "2020-01-01T00:00:00Z", 2));
        deviceService.update(sample("TILT", true, "2020-01-01T00:00:00Z", 3));
        for (int i = 0; i < 10; i++) deviceService.update(sample("TILT", true, "2020-01-01T00:00:00Z", 99));
        deviceService.update(sample("IMPACT", true, "2020-01-01T00:00:00Z", 4));
        deviceService.update(sample("MOVING", true, "2020-01-01T00:00:00Z", 5));
        deviceService.update(sample("DISARMED", false, "2020-01-01T00:00:00Z", 6));
        entityManager.clear();
        assertThat(repository.count()).isEqualTo(6);
        var rows = repository.findAll(org.springframework.data.domain.Sort.by("id"));
        assertThat(rows.get(0).getPreviousState()).isNull();
        assertThat(rows.get(1).getPreviousState()).isEqualTo("NORMAL");
        assertThat(rows.get(2).getPreviousState()).isEqualTo("MOVING");
        assertThat(rows.get(3).getPreviousState()).isEqualTo("TILT");
        assertThat(rows.get(4).getPreviousState()).isEqualTo("IMPACT");
        assertThat(rows.get(5).getPreviousState()).isEqualTo("MOVING");
        assertThat(rows.get(5).isArmed()).isFalse();
        var event = rows.get(2);
        assertThat(event.getX()).isEqualTo(3);
        assertThat(event.getY()).isEqualTo(-20);
        assertThat(event.getZ()).isEqualTo(1500);
        assertThat(event.getBase()).isEqualTo(3);
        assertThat(event.getChange()).isEqualTo(10);
        assertThat(event.getTemperature()).isEqualTo(26);
        assertThat(event.getReceivedAt()).isBetween(before, Instant.now());
        mvc.perform(get("/api/device/events").param("limit", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].state").value("DISARMED"))
            .andExpect(jsonPath("$[0].previousState").value("MOVING"))
            .andExpect(jsonPath("$[1].state").value("MOVING"));
    }

    @Test void armedChangeAloneAlsoCreatesAnEvent() {
        deviceService.update(sample("NORMAL", true, "2020-01-01T00:00:00Z", 1));
        deviceService.update(sample("NORMAL", false, "2020-01-01T00:00:00Z", 2));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test void emptyHistoryIsArrayAndInvalidLimitReturns400() throws Exception {
        mvc.perform(get("/api/device/events"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        for (String limit : new String[]{"0", "101", "-1", "abc"}) {
            mvc.perform(get("/api/device/events").param("limit", limit)).andExpect(status().isBadRequest());
        }
    }
}
