package com.physicscube.luma.sessions.web;

import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.SessionView;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sessions are cancelled, never deleted. */
@RestController
@RequestMapping("/api/v1/class-sessions")
class ClassSessionController {
    private final ClassSessionService sessions;

    ClassSessionController(ClassSessionService sessions) {
        this.sessions = sessions;
    }

    record CreateSessionRequest(@NotBlank @Size(max = 200) String title, @NotNull Instant startsAt,
                                @NotNull Instant endsAt, @Size(max = 64) String timeZone,
                                @NotNull List<UUID> batchIds) {
    }

    record UpdateSessionRequest(@NotBlank @Size(max = 200) String title, @NotNull Instant startsAt,
                                @NotNull Instant endsAt, @Size(max = 64) String timeZone,
                                @NotNull List<UUID> batchIds, @NotBlank String status,
                                @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<SessionView> list(JwtAuthenticationToken authentication, @RequestParam Instant from, @RequestParam Instant to,
                           @RequestParam(required = false) UUID batchId) {
        return sessions.list(CurrentAccount.from(authentication), from, to, batchId);
    }

    @GetMapping("/{sessionId}")
    SessionView get(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return sessions.get(CurrentAccount.from(authentication), sessionId);
    }

    @GetMapping("/{sessionId}/students")
    List<ExpectedStudent> expectedStudents(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return sessions.expectedStudents(CurrentAccount.from(authentication), sessionId);
    }

    @PostMapping
    ResponseEntity<SessionView> create(JwtAuthenticationToken authentication, @Valid @RequestBody CreateSessionRequest request) {
        SessionView created = sessions.create(CurrentAccount.from(authentication), request.title(), request.startsAt(),
                request.endsAt(), request.timeZone(), request.batchIds());
        return ResponseEntity.created(URI.create("/api/v1/class-sessions/" + created.id())).body(created);
    }

    @PutMapping("/{sessionId}")
    SessionView update(JwtAuthenticationToken authentication, @PathVariable UUID sessionId,
                       @Valid @RequestBody UpdateSessionRequest request) {
        return sessions.update(CurrentAccount.from(authentication), sessionId, request.title(), request.startsAt(),
                request.endsAt(), request.timeZone(), request.batchIds(), request.status(), request.version());
    }
}
