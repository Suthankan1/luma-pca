package com.physicscube.luma.zoom.web;

import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.zoom.ZoomParticipantService;
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

/** Who joined a Zoom occurrence and which student each Zoom email belongs to. Emails travel in bodies and query strings. */
@RestController
class ZoomParticipantController {
    private final ZoomParticipantService participants;

    ZoomParticipantController(ZoomParticipantService participants) {
        this.participants = participants;
    }

    /** @param studentId {@code null} marks the email "not a student" */
    record DecisionRequest(@NotBlank @Size(max = 254) String email, UUID studentId, @NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/api/v1/zoom/participants")
    ZoomParticipantService.OccurrenceParticipants list(JwtAuthenticationToken authentication,
                                                        @RequestParam String occurrence) {
        return participants.participants(CurrentAccount.from(authentication), occurrence);
    }

    @GetMapping("/api/v1/zoom/participants/unmatched")
    List<ZoomParticipantService.UnmatchedIdentity> unmatched(JwtAuthenticationToken authentication,
                                                             @RequestParam(defaultValue = "50") int limit) {
        return participants.unmatched(CurrentAccount.from(authentication), limit);
    }

    @GetMapping("/api/v1/zoom/identity-links")
    List<ZoomParticipantService.IdentityDecision> decisions(JwtAuthenticationToken authentication) {
        return participants.decisions(CurrentAccount.from(authentication));
    }

    record RemovalRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 300) String reason) {
    }

    @PostMapping("/api/v1/zoom/identity-links/removals")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void remove(JwtAuthenticationToken authentication, @Valid @RequestBody RemovalRequest request) {
        participants.removeDecision(CurrentAccount.from(authentication), request.email(), request.reason());
    }

    @PostMapping("/api/v1/zoom/identity-links")
    ZoomParticipantService.IdentityDecision decide(JwtAuthenticationToken authentication, @Valid @RequestBody DecisionRequest request) {
        return participants.decide(CurrentAccount.from(authentication), request.email(), request.studentId(), request.reason());
    }
}
