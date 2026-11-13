package com.physicscube.luma.attendance.web;

import com.physicscube.luma.attendance.ProvisionalAttendanceService;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AttendanceController {
    private final ProvisionalAttendanceService attendance;

    AttendanceController(ProvisionalAttendanceService attendance) {
        this.attendance = attendance;
    }

    @GetMapping("/api/v1/class-sessions/{sessionId}/attendance")
    ProvisionalAttendanceService.ProvisionalAttendance forClass(JwtAuthenticationToken authentication, @PathVariable UUID sessionId) {
        return attendance.forClass(CurrentAccount.from(authentication), sessionId);
    }
}
