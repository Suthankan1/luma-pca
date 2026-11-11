package com.physicscube.luma.shared.api;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {
    public record StatusResponse(String service, String status, String stage, Instant timestamp) {}

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse("luma-api", "UP", "BOOTSTRAP_ONLY", Instant.now());
    }
}
