package com.physicscube.luma.zoom.web;

import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.zoom.WebinarMonitorService;
import com.physicscube.luma.zoom.WebinarMonitorService.MonitorView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webinars LUMA monitors: staff give only a webinar ID or join link; title and schedule come from Zoom, read-only. There
 * is no route that changes a Zoom webinar. Staff only.
 */
@RestController
class WebinarMonitorController {
    private final WebinarMonitorService monitors;

    WebinarMonitorController(WebinarMonitorService monitors) {
        this.monitors = monitors;
    }

    @GetMapping("/api/v1/me/webinar-schedule")
    ResponseEntity<List<WebinarMonitorService.StudentSchedule>> mySchedule(JwtAuthenticationToken authentication,
                                                                            @RequestParam Instant from, @RequestParam Instant to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(monitors.scheduleForStudent(CurrentAccount.from(authentication), from, to));
    }

    record AddRequest(@NotBlank @Size(max = 500) String webinar) {
    }

    record ClassRequest(@Size(max = 200) String academyClassName) {
    }

    /** The Zoom run behind a class (204 when the class was not created by a Zoom run). */
    @GetMapping("/api/v1/class-sessions/{sessionId}/zoom-run")
    org.springframework.http.ResponseEntity<WebinarMonitorService.ClassRun> classRun(JwtAuthenticationToken authentication,
                                                                                     @PathVariable UUID sessionId) {
        return monitors.runForClass(CurrentAccount.from(authentication), sessionId)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/api/v1/zoom/monitors")
    List<MonitorView> list(JwtAuthenticationToken authentication) {
        return monitors.list(CurrentAccount.from(authentication));
    }

    @PostMapping("/api/v1/zoom/monitors")
    MonitorView add(JwtAuthenticationToken authentication, @Valid @RequestBody AddRequest request) {
        return monitors.add(CurrentAccount.from(authentication), request.webinar());
    }

    @PostMapping("/api/v1/zoom/monitors/{monitorId}/sync")
    MonitorView sync(JwtAuthenticationToken authentication, @PathVariable UUID monitorId) {
        return monitors.sync(CurrentAccount.from(authentication), monitorId);
    }

    @PostMapping("/api/v1/zoom/monitors/{monitorId}/stop")
    MonitorView stop(JwtAuthenticationToken authentication, @PathVariable UUID monitorId) {
        return monitors.setActive(CurrentAccount.from(authentication), monitorId, false);
    }

    @PostMapping("/api/v1/zoom/monitors/{monitorId}/resume")
    MonitorView resume(JwtAuthenticationToken authentication, @PathVariable UUID monitorId) {
        return monitors.setActive(CurrentAccount.from(authentication), monitorId, true);
    }

    @PutMapping("/api/v1/zoom/monitors/{monitorId}/academy-class")
    MonitorView academyClass(JwtAuthenticationToken authentication, @PathVariable UUID monitorId,
                             @Valid @RequestBody ClassRequest request) {
        return monitors.setAcademyClass(CurrentAccount.from(authentication), monitorId, request.academyClassName());
    }
}
