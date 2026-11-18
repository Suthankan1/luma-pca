package com.physicscube.luma.students.web;

import com.physicscube.luma.identity.AuthService.LoginOutcome;
import com.physicscube.luma.identity.InvalidCredentialsException;
import com.physicscube.luma.students.StudentIdLoginService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Academy-requested student sign-in. Answers 404 while switched off; the same 401 for every refusal. Responses
 * have the same shape as {@code POST /api/v1/auth/login}.
 */
@RestController
class StudentIdLoginController {
    private final StudentIdLoginService login;

    StudentIdLoginController(StudentIdLoginService login) {
        this.login = login;
    }

    record StudentIdLoginRequest(@NotBlank @Size(max = 60) String studentId, @NotBlank @Size(max = 60) String password) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {
    }

    record MfaRequiredResponse(boolean mfaRequired, String mfaToken, long mfaExpiresIn, boolean mfaEnrolled) {
    }

    record Availability(boolean enabled) {
    }

    /** Lets the sign-in page offer the student ID form only when it is switched on. */
    @GetMapping("/api/v1/auth/student-id-login")
    Availability availability() {
        return new Availability(login.enabled());
    }

    @PostMapping("/api/v1/auth/student-id-login")
    Object login(@Valid @RequestBody StudentIdLoginRequest request) {
        LoginOutcome outcome = login.login(request.studentId(), request.password());
        if (outcome.challenge() != null) {
            return new MfaRequiredResponse(true, outcome.challenge().mfaToken(), outcome.challenge().expiresInSeconds(),
                    outcome.challenge().enrolled());
        }
        return new TokenResponse(outcome.tokens().accessToken(), "Bearer", outcome.tokens().expiresInSeconds(),
                outcome.tokens().refreshToken(), outcome.tokens().refreshExpiresInSeconds());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }
}
