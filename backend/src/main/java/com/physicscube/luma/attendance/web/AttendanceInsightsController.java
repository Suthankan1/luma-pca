package com.physicscube.luma.attendance.web;

import com.physicscube.luma.attendance.AttendanceInsightsService;
import com.physicscube.luma.attendance.AttendanceInsightsService.ClassTotals;
import com.physicscube.luma.attendance.AttendanceInsightsService.CompletedClassAttendance;
import com.physicscube.luma.attendance.AttendanceInsightsService.Dashboard;
import com.physicscube.luma.attendance.AttendanceInsightsService.StudentOverview;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attendance dashboards over a date range (days in Asia/Colombo, both included; default the last 30 days, at most 92). */
@RestController
class AttendanceInsightsController {
    private final AttendanceInsightsService insights;

    AttendanceInsightsController(AttendanceInsightsService insights) {
        this.insights = insights;
    }

    /** Staff: every student of the selected course or batch over the classes held in the range. */
    @GetMapping("/api/v1/attendance/dashboard")
    Dashboard dashboard(JwtAuthenticationToken authentication,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) UUID courseId, @RequestParam(required = false) UUID batchId) {
        return insights.dashboard(CurrentAccount.from(authentication), AttendanceInsightsService.range(from, to), courseId, batchId);
    }

    @GetMapping("/api/v1/attendance/class-totals")
    List<ClassTotals> classTotals(JwtAuthenticationToken authentication,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return insights.classTotals(CurrentAccount.from(authentication), AttendanceInsightsService.range(from, to));
    }

    @GetMapping("/api/v1/attendance/latest-completed")
    ResponseEntity<CompletedClassAttendance> latestCompleted(JwtAuthenticationToken authentication) {
        return insights.latestCompleted(CurrentAccount.from(authentication)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/v1/attendance/recent-completed")
    List<CompletedClassAttendance> recentCompleted(JwtAuthenticationToken authentication) {
        return insights.recentCompleted(CurrentAccount.from(authentication));
    }

    /** Owners and administrators: one student's courses and every class, with each join and leave. */
    @GetMapping("/api/v1/students/{studentId}/attendance-overview")
    StudentOverview student(JwtAuthenticationToken authentication, @PathVariable UUID studentId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return insights.forStudent(CurrentAccount.from(authentication), studentId, AttendanceInsightsService.range(from, to));
    }

    /** A student's own: the student is taken from the sign-in, never from the request. */
    @GetMapping("/api/v1/me/attendance-overview")
    StudentOverview mine(JwtAuthenticationToken authentication,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return insights.mine(CurrentAccount.from(authentication), AttendanceInsightsService.range(from, to));
    }
}
