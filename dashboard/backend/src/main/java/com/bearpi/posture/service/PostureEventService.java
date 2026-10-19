package com.bearpi.posture.service;

import com.bearpi.posture.entity.PostureEvent;
import com.bearpi.posture.repository.PostureEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class PostureEventService {
    private static final Logger log = LoggerFactory.getLogger(PostureEventService.class);
    private final PostureEventRepository repository;

    public PostureEventService(PostureEventRepository repository) {
        this.repository = repository;
    }

    /** DeviceStateService supplies an immutable snapshot only when state/arming changes. */
    @Async("eventPersistenceExecutor")
    public void recordAsync(PostureEvent event) {
        try {
            repository.saveAndFlush(event);
        } catch (DataAccessException | TransactionException e) {
            log.error("[Events] Failed to save {} -> {} receivedAt={}; event not persisted",
                    event.getPreviousState(), event.getState(), event.getReceivedAt(), e);
        }
    }

    public List<PostureEvent> findLatest(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..100");
        }
        return repository.findAll(PageRequest.of(0, limit,
                Sort.by(Sort.Direction.DESC, "receivedAt", "id"))).getContent();
    }
}
