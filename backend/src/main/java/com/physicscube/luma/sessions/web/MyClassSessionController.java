package com.physicscube.luma.sessions.web;

import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.StudentClassView;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A student's own classes. Staff use /api/v1/class-sessions. */
@RestController
class MyClassSessionController {
    private final ClassSessionService sessions;

    MyClassSessionController(ClassSessionService sessions) {
        this.sessions = sessions;
    }

    /** Classes starting in [from, to), at most 92 days, for batches the student is enrolled in on each class's date. */
    @GetMapping("/api/v1/me/class-sessions")
    List<StudentClassView> mine(JwtAuthenticationToken authentication, @RequestParam Instant from, @RequestParam Instant to) {
        return sessions.mine(CurrentAccount.from(authentication), from, to);
    }
}
