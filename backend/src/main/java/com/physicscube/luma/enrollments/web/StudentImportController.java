package com.physicscube.luma.enrollments.web;

import com.physicscube.luma.enrollments.StudentImportService;
import com.physicscube.luma.enrollments.StudentImportService.Result;
import com.physicscube.luma.enrollments.StudentImportService.Row;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Bulk student creation; problems come back row by row with 200, and nothing is written unless every row is valid. */
@RestController
class StudentImportController {
    private final StudentImportService imports;

    StudentImportController(StudentImportService imports) {
        this.imports = imports;
    }

    record ImportRequest(@NotNull List<Row> rows, UUID batchId, LocalDate enrolledOn, Boolean dryRun) {
    }

    @PostMapping("/api/v1/students/import")
    Result importStudents(JwtAuthenticationToken authentication, @RequestBody ImportRequest request) {
        return imports.importStudents(CurrentAccount.from(authentication), request.rows(), request.batchId(),
                request.enrolledOn(), Boolean.TRUE.equals(request.dryRun()));
    }
}
