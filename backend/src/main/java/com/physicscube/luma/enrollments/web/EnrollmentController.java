package com.physicscube.luma.enrollments.web;

import com.physicscube.luma.enrollments.EnrollmentService;
import com.physicscube.luma.enrollments.EnrollmentView;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Enrollments are withdrawn, never deleted. */
@RestController
class EnrollmentController {
    private final EnrollmentService enrollments;

    EnrollmentController(EnrollmentService enrollments) {
        this.enrollments = enrollments;
    }

    record EnrollRequest(@NotNull UUID studentId, @NotNull LocalDate enrolledOn) {
    }

    record UpdateEnrollmentRequest(@NotNull LocalDate enrolledOn, LocalDate withdrawnOn,
                                   @NotNull @PositiveOrZero Long version) {
        @AssertTrue(message = "withdrawnOn must not be before enrolledOn")
        boolean isDateRangeValid() {
            return enrolledOn == null || withdrawnOn == null || !withdrawnOn.isBefore(enrolledOn);
        }
    }

    @GetMapping("/api/v1/batches/{batchId}/enrollments")
    List<EnrollmentView> roster(JwtAuthenticationToken authentication, @PathVariable UUID batchId) {
        return enrollments.roster(CurrentAccount.from(authentication), batchId);
    }

    @PostMapping("/api/v1/batches/{batchId}/enrollments")
    ResponseEntity<EnrollmentView> enroll(JwtAuthenticationToken authentication, @PathVariable UUID batchId,
                                          @Valid @RequestBody EnrollRequest request) {
        EnrollmentView created = enrollments.enroll(CurrentAccount.from(authentication), batchId, request.studentId(),
                request.enrolledOn());
        return ResponseEntity.created(URI.create("/api/v1/enrollments/" + created.id())).body(created);
    }

    @PutMapping("/api/v1/enrollments/{enrollmentId}")
    EnrollmentView update(JwtAuthenticationToken authentication, @PathVariable UUID enrollmentId,
                          @Valid @RequestBody UpdateEnrollmentRequest request) {
        return enrollments.update(CurrentAccount.from(authentication), enrollmentId, request.enrolledOn(),
                request.withdrawnOn(), request.version());
    }

    @GetMapping("/api/v1/students/{studentId}/enrollments")
    List<EnrollmentView> forStudent(JwtAuthenticationToken authentication, @PathVariable UUID studentId) {
        return enrollments.forStudent(CurrentAccount.from(authentication), studentId);
    }

    /** The signed-in student's own enrollments. */
    @GetMapping("/api/v1/me/enrollments")
    List<EnrollmentView> mine(JwtAuthenticationToken authentication) {
        return enrollments.mine(CurrentAccount.from(authentication));
    }
}
