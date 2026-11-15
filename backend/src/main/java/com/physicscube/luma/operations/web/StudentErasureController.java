package com.physicscube.luma.operations.web;

import com.physicscube.luma.operations.StudentErasureService;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StudentErasureController {
    private final StudentErasureService erasure;

    StudentErasureController(StudentErasureService erasure) {
        this.erasure = erasure;
    }

    record ErasureRequest(@NotBlank @Size(max = 60) String confirmStudentNumber, @NotBlank @Size(max = 300) String reason) {
    }

    @PostMapping("/api/v1/students/{studentId}/erasure")
    StudentErasureService.ErasureResult erase(JwtAuthenticationToken authentication, @PathVariable UUID studentId,
                                              @Valid @RequestBody ErasureRequest request) {
        return erasure.erase(CurrentAccount.from(authentication), studentId, request.confirmStudentNumber(), request.reason());
    }
}
