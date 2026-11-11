package com.physicscube.luma.students.web;

import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.students.StudentPage;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Students are deactivated, never deleted, so attendance history keeps its owner. */
@RestController
class StudentController {
    private final StudentService students;

    StudentController(StudentService students) {
        this.students = students;
    }

    record CreateStudentRequest(@NotNull @Pattern(regexp = Codes.PATTERN) String studentNumber,
                                @NotBlank @Size(max = 200) String fullName,
                                @NotBlank @Size(max = 254) String email) {
    }

    record UpdateStudentRequest(@NotBlank @Size(max = 200) String fullName,
                                @NotNull Boolean active,
                                @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping("/api/v1/students")
    StudentPage list(JwtAuthenticationToken authentication, @RequestParam(required = false) Boolean active,
                     @RequestParam(required = false) String after, @RequestParam(defaultValue = "50") int limit) {
        return students.list(CurrentAccount.from(authentication), active, after, limit);
    }

    @GetMapping("/api/v1/students/{studentId}")
    StudentView get(JwtAuthenticationToken authentication, @PathVariable UUID studentId) {
        return students.get(CurrentAccount.from(authentication), studentId);
    }

    @PostMapping("/api/v1/students")
    ResponseEntity<StudentView> create(JwtAuthenticationToken authentication, @Valid @RequestBody CreateStudentRequest request) {
        StudentView created = students.create(CurrentAccount.from(authentication), request.studentNumber(),
                request.fullName(), request.email());
        return ResponseEntity.created(URI.create("/api/v1/students/" + created.id())).body(created);
    }

    @PutMapping("/api/v1/students/{studentId}")
    StudentView update(JwtAuthenticationToken authentication, @PathVariable UUID studentId,
                       @Valid @RequestBody UpdateStudentRequest request) {
        return students.update(CurrentAccount.from(authentication), studentId, request.fullName(), request.active(),
                request.version());
    }

    /** The signed-in student's own record. */
    @GetMapping("/api/v1/me/student")
    StudentView mine(JwtAuthenticationToken authentication) {
        return students.mine(CurrentAccount.from(authentication));
    }
}
