package com.bearpi.posture.controller;

import com.bearpi.posture.model.PostureStatus;
import com.bearpi.posture.service.DeviceStateService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final DeviceStateService deviceStateService;

    public DeviceController(DeviceStateService deviceStateService) {
        this.deviceStateService = deviceStateService;
    }

    @GetMapping("/status")
    public ResponseEntity<PostureStatus> getLatestStatus() {

        PostureStatus status =
                deviceStateService.getLatestStatus();

        if (status == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(status);
    }
}