package com.physicscube.luma.engagement.web;

import com.physicscube.luma.engagement.EngagementService;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EngagementController {
    private final EngagementService engagement;

    EngagementController(EngagementService engagement) {
        this.engagement = engagement;
    }

    record SettingsRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 2000) String consentText, Long version) {
    }

    record ConsentRequest(@NotNull Boolean granted, @NotNull Boolean leaderboardOptIn, @NotNull Integer consentVersion) {
    }

    record ReactionRequest(@NotBlank String kind) {
    }

    record PulseRequest(@NotBlank @Size(max = 300) String prompt, @NotNull @Size(min = 2, max = 5) List<String> options, @NotNull Integer minutes) {
    }

    record AnswerRequest(@NotNull Integer option) {
    }

    @GetMapping("/api/v1/engagement-settings")
    ResponseEntity<EngagementService.Settings> settings(JwtAuthenticationToken authentication) {
        return engagement.settings(CurrentAccount.from(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/api/v1/engagement-settings")
    EngagementService.Settings configure(JwtAuthenticationToken authentication, @Valid @RequestBody SettingsRequest request) {
        return engagement.configure(CurrentAccount.from(authentication), request.enabled(), request.consentText(), request.version());
    }

    @GetMapping("/api/v1/me/engagement-consent")
    EngagementService.MyConsent myConsent(JwtAuthenticationToken authentication) {
        return engagement.myConsent(CurrentAccount.from(authentication));
    }

    @PutMapping("/api/v1/me/engagement-consent")
    EngagementService.MyConsent decide(JwtAuthenticationToken authentication, @Valid @RequestBody ConsentRequest request) {
        return engagement.decide(CurrentAccount.from(authentication), request.granted(), request.leaderboardOptIn(), request.consentVersion());
    }

    @GetMapping("/api/v1/me/class-sessions/{sessionId}/engagement")
    EngagementService.ClassEngagement forStudent(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return engagement.forStudent(CurrentAccount.from(authentication), sessionId);
    }

    @PostMapping("/api/v1/me/class-sessions/{sessionId}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void react(JwtAuthenticationToken authentication, @PathVariable UUID sessionId, @Valid @RequestBody ReactionRequest request) {
        engagement.react(CurrentAccount.from(authentication), sessionId, request.kind());
    }

    @PostMapping("/api/v1/me/class-sessions/{sessionId}/pulses/{questionId}/answers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void answer(JwtAuthenticationToken authentication, @PathVariable UUID sessionId, @PathVariable UUID questionId,
                @Valid @RequestBody AnswerRequest request) {
        engagement.answer(CurrentAccount.from(authentication), sessionId, questionId, request.option());
    }

    @GetMapping("/api/v1/class-sessions/{sessionId}/engagement")
    EngagementService.ClassEngagement forStaff(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return engagement.forStaff(CurrentAccount.from(authentication), sessionId);
    }

    @PostMapping("/api/v1/class-sessions/{sessionId}/pulses")
    @ResponseStatus(HttpStatus.CREATED)
    EngagementService.PulseView openPulse(JwtAuthenticationToken authentication, @PathVariable UUID sessionId,
                                          @Valid @RequestBody PulseRequest request) {
        return engagement.openPulse(CurrentAccount.from(authentication), sessionId, request.prompt(), request.options(), request.minutes());
    }

    @PostMapping("/api/v1/class-sessions/{sessionId}/pulses/{questionId}/closure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void closePulse(JwtAuthenticationToken authentication, @PathVariable UUID sessionId, @PathVariable UUID questionId) {
        engagement.closePulse(CurrentAccount.from(authentication), sessionId, questionId);
    }

    @GetMapping("/api/v1/courses/{courseId}/engagement-ranking")
    EngagementService.Ranking courseRanking(JwtAuthenticationToken authentication, @PathVariable UUID courseId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "10") int limit) {
        return engagement.courseRanking(CurrentAccount.from(authentication), courseId, from, to, limit);
    }

    @GetMapping({"/api/v1/batches/{scopeId}/engagement-ranking/export", "/api/v1/courses/{scopeId}/engagement-ranking/export"})
    ResponseEntity<byte[]> exportRanking(JwtAuthenticationToken authentication, jakarta.servlet.http.HttpServletRequest request,
                                         @PathVariable UUID scopeId,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "10") int limit) {
        boolean course = request.getRequestURI().startsWith("/api/v1/courses/");
        String csv = engagement.exportRanking(CurrentAccount.from(authentication), course ? null : scopeId, course ? scopeId : null, from, to, limit);
        return ResponseEntity.ok().contentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"engagement-ranking-" + from + "-to-" + to + ".csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/api/v1/batches/{batchId}/engagement-ranking")
    EngagementService.Ranking ranking(JwtAuthenticationToken authentication, @PathVariable UUID batchId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(defaultValue = "10") int limit) {
        return engagement.ranking(CurrentAccount.from(authentication), batchId, from, to, limit);
    }
}
