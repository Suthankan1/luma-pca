package com.physicscube.luma.attendance.web;

import com.physicscube.luma.attendance.AttendanceRecordService;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AttendanceRecordController {
    private final AttendanceRecordService records;

    AttendanceRecordController(AttendanceRecordService records) {
        this.records = records;
    }

    record CorrectionRequest(@NotBlank String outcome, @NotBlank @Size(max = 500) String reason, @NotNull Long version) {
    }

    record AppealRequest(@NotBlank @Size(max = 1000) String message) {
    }

    /** @param decision ACCEPT or REJECT; @param outcome required when accepting */
    record ResolutionRequest(@NotBlank String decision, String outcome, @NotBlank @Size(max = 500) String note) {
    }

    @PostMapping("/api/v1/attendance-records/{recordId}/corrections")
    AttendanceRecordService.RecordView correct(JwtAuthenticationToken authentication, @PathVariable UUID recordId,
                                               @Valid @RequestBody CorrectionRequest request) {
        return records.correct(CurrentAccount.from(authentication), recordId, request.outcome(), request.reason(), request.version());
    }

    @GetMapping("/api/v1/attendance-records/{recordId}/corrections")
    List<AttendanceRecordService.Correction> corrections(JwtAuthenticationToken authentication, @PathVariable UUID recordId) {
        return records.corrections(CurrentAccount.from(authentication), recordId);
    }

    @GetMapping("/api/v1/students/{studentId}/attendance")
    List<AttendanceRecordService.RecordView> studentHistory(JwtAuthenticationToken authentication, @PathVariable UUID studentId) {
        return records.studentHistory(CurrentAccount.from(authentication), studentId);
    }

    @GetMapping("/api/v1/me/attendance")
    List<AttendanceRecordService.RecordView> mine(JwtAuthenticationToken authentication) {
        return records.mine(CurrentAccount.from(authentication));
    }

    @PostMapping("/api/v1/me/attendance/{recordId}/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    AttendanceRecordService.Appeal appeal(JwtAuthenticationToken authentication, @PathVariable UUID recordId,
                                          @Valid @RequestBody AppealRequest request) {
        return records.appeal(CurrentAccount.from(authentication), recordId, request.message());
    }

    @GetMapping("/api/v1/attendance-appeals")
    List<AttendanceRecordService.Appeal> appeals(JwtAuthenticationToken authentication, @RequestParam(required = false) String status) {
        return records.appeals(CurrentAccount.from(authentication), status);
    }

    @PostMapping("/api/v1/attendance-appeals/{appealId}/resolution")
    AttendanceRecordService.Appeal resolve(JwtAuthenticationToken authentication, @PathVariable UUID appealId,
                                           @Valid @RequestBody ResolutionRequest request) {
        boolean accept = switch (request.decision()) {
            case "ACCEPT" -> true;
            case "REJECT" -> false;
            default -> throw new com.physicscube.luma.shared.api.InvalidRequestException("decision must be ACCEPT or REJECT");
        };
        return records.resolve(CurrentAccount.from(authentication), appealId, accept, request.outcome(), request.note());
    }
}
