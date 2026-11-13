package com.physicscube.luma.attendance.web;

import com.physicscube.luma.attendance.AttendanceExportService;
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

    AttendanceController(ProvisionalAttendanceService attendance, AttendanceExportService exports) {
        this.attendance = attendance;
        this.exports = exports;
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
