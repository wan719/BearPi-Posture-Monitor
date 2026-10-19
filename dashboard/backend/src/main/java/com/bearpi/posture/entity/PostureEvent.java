package com.bearpi.posture.entity;

import com.bearpi.posture.model.PostureStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** Immutable snapshot of a device state transition. */
@Entity
@Table(name = "posture_event")
public class PostureEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "previous_state", length = 20)
    private String previousState;
    @Column(nullable = false)
    private boolean armed;
    @Column(nullable = false, length = 20)
    private String state;
    @Column(nullable = false)
    private int x;
    @Column(nullable = false)
    private int y;
    @Column(nullable = false)
    private int z;
    @Column(name = "base", nullable = false)
    private int base;
    @Column(name = "`change`", nullable = false)
    private int change;
    @Column(nullable = false)
    private int temperature;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "received_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant receivedAt;

    protected PostureEvent() { }

    public PostureEvent(String previousState, PostureStatus status) {
        this.previousState = previousState;
        armed = status.isArmed(); state = status.getState();
        x = status.getX(); y = status.getY(); z = status.getZ();
        base = status.getBase(); change = status.getChange();
        temperature = status.getTemperature(); receivedAt = status.getReceivedAt();
    }

    public String getPreviousState() { return previousState; }
    public Long getId() { return id; }
    public boolean isArmed() { return armed; }
    public String getState() { return state; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getBase() { return base; }
    public int getChange() { return change; }
    public int getTemperature() { return temperature; }
    public Instant getReceivedAt() { return receivedAt; }
}
