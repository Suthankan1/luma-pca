package com.physicscube.luma.zoom.web;

import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.zoom.ZoomLookupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Check a webinar by join link or ID. Links and occurrence UUIDs travel in query strings and bodies, never in paths. */
@RestController
class ZoomLookupController {
    private final ZoomLookupService lookups;

    ZoomLookupController(ZoomLookupService lookups) {
        this.lookups = lookups;
    }

    record RunRequest(@NotBlank @Size(max = 500) String webinar, @NotBlank @Size(max = 100) String occurrenceUuid) {
    }

    record LinkRequest(@NotBlank @Size(max = 500) String webinar, @NotBlank @Size(max = 100) String occurrenceUuid,
                       @NotNull UUID sessionId, @NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/api/v1/zoom/webinar-lookup")
    ZoomLookupService.WebinarLookup lookup(JwtAuthenticationToken authentication, @RequestParam String webinar) {
        return lookups.lookup(CurrentAccount.from(authentication), webinar);
    }

    @GetMapping("/api/v1/zoom/webinar-lookup/attendance")
    ZoomLookupService.RunAttendance attendance(JwtAuthenticationToken authentication, @RequestParam String webinar,
                                               @RequestParam String occurrence) {
        return lookups.attendance(CurrentAccount.from(authentication), webinar, occurrence);
    }

    @PostMapping("/api/v1/zoom/webinar-lookup/reports")
    ZoomLookupService.RunAttendance fetch(JwtAuthenticationToken authentication, @Valid @RequestBody RunRequest request) {
        return lookups.fetch(CurrentAccount.from(authentication), request.webinar(), request.occurrenceUuid());
    }

    @PostMapping("/api/v1/zoom/webinar-lookup/links")
    ZoomLookupService.RunAttendance link(JwtAuthenticationToken authentication, @Valid @RequestBody LinkRequest request) {
        return lookups.link(CurrentAccount.from(authentication), request.webinar(), request.occurrenceUuid(), request.sessionId(),
                request.reason());
    }
}
