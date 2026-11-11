package com.physicscube.luma.batches.web;

import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.batches.BatchView;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Batches are deactivated, never deleted, and cannot be moved to another course. */
@RestController
@RequestMapping("/api/v1/batches")
class BatchController {
    private final BatchService batches;

    BatchController(BatchService batches) {
        this.batches = batches;
    }

    record CreateBatchRequest(@NotNull UUID courseId,
                              @NotNull @Pattern(regexp = Codes.PATTERN) String code,
                              @NotBlank @Size(max = 200) String name,
                              @NotNull LocalDate startsOn,
                              LocalDate endsOn) {
        @AssertTrue(message = "endsOn must not be before startsOn")
        boolean isDateRangeValid() {
            return startsOn == null || endsOn == null || !endsOn.isBefore(startsOn);
        }
    }

    record UpdateBatchRequest(@NotNull @Pattern(regexp = Codes.PATTERN) String code,
                              @NotBlank @Size(max = 200) String name,
                              @NotNull LocalDate startsOn,
                              LocalDate endsOn,
                              @NotNull Boolean active,
                              @NotNull @PositiveOrZero Long version) {
        @AssertTrue(message = "endsOn must not be before startsOn")
        boolean isDateRangeValid() {
            return startsOn == null || endsOn == null || !endsOn.isBefore(startsOn);
        }
    }

    @GetMapping
    List<BatchView> list(JwtAuthenticationToken authentication, @RequestParam(required = false) UUID courseId) {
        return batches.list(CurrentAccount.from(authentication), courseId);
    }

    @GetMapping("/{batchId}")
    BatchView get(JwtAuthenticationToken authentication, @PathVariable UUID batchId) {
        return batches.get(CurrentAccount.from(authentication), batchId);
    }

    @PostMapping
    ResponseEntity<BatchView> create(JwtAuthenticationToken authentication, @Valid @RequestBody CreateBatchRequest request) {
        BatchView created = batches.create(CurrentAccount.from(authentication), request.courseId(), request.code(),
                request.name(), request.startsOn(), request.endsOn());
        return ResponseEntity.created(URI.create("/api/v1/batches/" + created.id())).body(created);
    }

    @PutMapping("/{batchId}")
    BatchView update(JwtAuthenticationToken authentication, @PathVariable UUID batchId,
                     @Valid @RequestBody UpdateBatchRequest request) {
        return batches.update(CurrentAccount.from(authentication), batchId, request.code(), request.name(),
                request.startsOn(), request.endsOn(), request.active(), request.version());
    }
}
