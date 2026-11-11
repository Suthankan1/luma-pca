package com.physicscube.luma.batches.web;

import com.physicscube.luma.batches.AssignmentView;
import com.physicscube.luma.batches.StaffAssignmentService;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StaffAssignmentController {
    private final StaffAssignmentService assignments;

    StaffAssignmentController(StaffAssignmentService assignments) {
        this.assignments = assignments;
    }

    record AssignRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 30) String role) {
    }

    @GetMapping("/api/v1/batches/{batchId}/assignments")
    List<AssignmentView> forBatch(JwtAuthenticationToken authentication, @PathVariable UUID batchId) {
        return assignments.forBatch(CurrentAccount.from(authentication), batchId);
    }

    @PostMapping("/api/v1/batches/{batchId}/assignments")
    ResponseEntity<AssignmentView> assign(JwtAuthenticationToken authentication, @PathVariable UUID batchId,
                                          @Valid @RequestBody AssignRequest request) {
        AssignmentView created = assignments.assign(CurrentAccount.from(authentication), batchId, request.email(), request.role());
        return ResponseEntity.created(URI.create("/api/v1/assignments/" + created.id())).body(created);
    }

    @DeleteMapping("/api/v1/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unassign(JwtAuthenticationToken authentication, @PathVariable UUID assignmentId) {
        assignments.unassign(CurrentAccount.from(authentication), assignmentId);
    }

    /** The signed-in instructor's or attendance officer's own batches. */
    @GetMapping("/api/v1/me/assignments")
    List<AssignmentView> mine(JwtAuthenticationToken authentication) {
        return assignments.mine(CurrentAccount.from(authentication));
    }
}
