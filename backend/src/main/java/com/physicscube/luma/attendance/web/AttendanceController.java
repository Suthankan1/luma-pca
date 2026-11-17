package com.physicscube.luma.attendance.web;

import com.physicscube.luma.attendance.AttendanceExportService;
import com.physicscube.luma.attendance.AttendanceFinalisationService;
import com.physicscube.luma.attendance.AttendanceSummaryService;
import com.physicscube.luma.attendance.AttendancePolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.physicscube.luma.attendance.ProvisionalAttendanceService;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AttendanceController {
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ProvisionalAttendanceService attendance;
    private final AttendanceExportService exports;
    private final AttendancePolicyService policies;
    private final AttendanceFinalisationService finalisation;
    private final AttendanceSummaryService summaries;

    AttendanceController(ProvisionalAttendanceService attendance, AttendanceExportService exports, AttendancePolicyService policies,
                         AttendanceFinalisationService finalisation, AttendanceSummaryService summaries) {
        this.attendance = attendance;
        this.exports = exports;
        this.policies = policies;
        this.finalisation = finalisation;
        this.summaries = summaries;
    }

    @GetMapping("/api/v1/batches/{batchId}/attendance/summary")
    AttendanceSummaryService.BatchSummary batchSummary(JwtAuthenticationToken authentication, @PathVariable UUID batchId,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return summaries.forBatch(CurrentAccount.from(authentication), batchId, from, to);
    }

    @GetMapping("/api/v1/attendance/summary")
    AttendanceSummaryService.AcademySummary academySummary(JwtAuthenticationToken authentication,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return summaries.forAcademy(CurrentAccount.from(authentication), from, to);
    }

    record PolicyRequest(@NotNull Integer minConnectedPercent, @NotNull Integer lateAfterMinutes, @NotBlank @Size(max = 500) String description,
                         Long version) {
    }

    /** 204 while the academy has not recorded a rule. */
    @GetMapping("/api/v1/attendance-policy")
    ResponseEntity<AttendancePolicyService.PolicyView> policy(JwtAuthenticationToken authentication) {
        return policies.get(CurrentAccount.from(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/api/v1/attendance-policy")
    AttendancePolicyService.PolicyView setPolicy(JwtAuthenticationToken authentication, @Valid @RequestBody PolicyRequest request) {
        return policies.set(CurrentAccount.from(authentication), request.minConnectedPercent(), request.lateAfterMinutes(),
                request.description(), request.version());
    }

    record RecalculationRequest(@NotBlank @Size(max = 300) String reason) {
    }

    @PostMapping("/api/v1/class-sessions/{sessionId}/attendance/recalculation")
    AttendanceFinalisationService.RecalculationResult recalculate(JwtAuthenticationToken authentication, @PathVariable UUID sessionId,
                                                                  @Valid @RequestBody RecalculationRequest request) {
        return finalisation.recalculate(CurrentAccount.from(authentication), sessionId, request.reason());
    }

    @PostMapping("/api/v1/class-sessions/{sessionId}/attendance/finalisation")
    AttendanceFinalisationService.FinalisationResult finalise(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return finalisation.finalise(CurrentAccount.from(authentication), sessionId);
    }

    @GetMapping("/api/v1/class-sessions/{sessionId}/attendance")
    ProvisionalAttendanceService.ProvisionalAttendance forClass(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return attendance.forClass(CurrentAccount.from(authentication), sessionId);
    }

    @GetMapping("/api/v1/class-sessions/{sessionId}/attendance/export")
    ResponseEntity<byte[]> exportClass(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return csv(exports.forClass(CurrentAccount.from(authentication), sessionId));
    }

    @GetMapping("/api/v1/batches/{batchId}/attendance/export")
    ResponseEntity<byte[]> exportBatch(JwtAuthenticationToken authentication, @PathVariable UUID batchId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return csv(exports.forBatch(CurrentAccount.from(authentication), batchId, from, to));
    }

    /** Personal data: a download, never cached by the browser or a proxy. */
    private static ResponseEntity<byte[]> csv(AttendanceExportService.CsvFile file) {
        return ResponseEntity.ok().contentType(CSV).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content().getBytes(StandardCharsets.UTF_8));
    }
}
