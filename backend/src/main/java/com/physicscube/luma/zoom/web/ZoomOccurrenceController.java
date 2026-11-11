package com.physicscube.luma.zoom.web;

import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.zoom.OccurrenceView;
import com.physicscube.luma.zoom.ZoomOccurrenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Occurrence UUIDs can contain "/" and "+", so they travel in bodies and query strings, never in paths. */
@RestController
class ZoomOccurrenceController {
    private final ZoomOccurrenceService occurrences;

    ZoomOccurrenceController(ZoomOccurrenceService occurrences) {
        this.occurrences = occurrences;
    }

    /** @param sessionId {@code null} marks the occurrence "not a class" */
    record DecisionRequest(@NotBlank @Size(max = 100) String occurrenceUuid, UUID sessionId,
                           @NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/api/v1/zoom/occurrences")
    List<OccurrenceView> list(JwtAuthenticationToken authentication, @RequestParam(required = false) Boolean decided,
                              @RequestParam(required = false) UUID sessionId, @RequestParam(defaultValue = "50") int limit) {
        return occurrences.list(CurrentAccount.from(authentication), decided, sessionId, limit);
    }

    @PostMapping("/api/v1/zoom/occurrence-links")
    OccurrenceView decide(JwtAuthenticationToken authentication, @Valid @RequestBody DecisionRequest request) {
        return occurrences.decide(CurrentAccount.from(authentication), request.occurrenceUuid(), request.sessionId(),
                request.reason());
    }

    @PostMapping("/api/v1/zoom/occurrences/auto-link")
    ZoomOccurrenceService.AutoLinkResult autoLink(JwtAuthenticationToken authentication) {
        return occurrences.autoLinkUndecided(CurrentAccount.from(authentication));
    }
}
