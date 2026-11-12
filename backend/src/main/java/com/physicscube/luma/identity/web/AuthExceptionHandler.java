package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.InvalidCredentialsException;
import com.physicscube.luma.identity.InvalidInvitationException;
import com.physicscube.luma.identity.InvalidSessionException;
import com.physicscube.luma.identity.InvalidMfaException;
import com.physicscube.luma.identity.InvalidPasswordResetException;
import com.physicscube.luma.identity.PasswordResetUnavailableException;
import com.physicscube.luma.identity.domain.PasswordPolicyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** These exception messages are written to be shown to callers and contain no input values. */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {
    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(InvalidSessionException.class)
    ProblemDetail invalidSession(InvalidSessionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(InvalidMfaException.class)
    ProblemDetail invalidMfa(InvalidMfaException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(InvalidInvitationException.class)
    ProblemDetail invalidInvitation(InvalidInvitationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidPasswordResetException.class)
    ProblemDetail invalidPasswordReset(InvalidPasswordResetException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PasswordResetUnavailableException.class)
    ProblemDetail passwordResetUnavailable(PasswordResetUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(PasswordPolicyException.class)
    ProblemDetail passwordPolicy(PasswordPolicyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
