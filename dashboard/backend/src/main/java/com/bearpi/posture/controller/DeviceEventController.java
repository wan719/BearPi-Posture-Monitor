package com.bearpi.posture.controller;

import com.bearpi.posture.entity.PostureEvent;
import com.bearpi.posture.service.PostureEventService;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/device/events")
public class DeviceEventController {
    private final PostureEventService service;

    public DeviceEventController(PostureEventService service) { this.service = service; }

    @GetMapping
    public List<PostureEvent> getEvents(@RequestParam(defaultValue = "20") int limit) {
        return service.findLatest(limit);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ProblemDetail databaseUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Event history is temporarily unavailable");
    }
}
