package com.physicscube.luma.students;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Academy-requested student sign-in: the Academy student ID is both username and password. Off by default; it
 * is guessable by design and needs the Academy's written risk acceptance before it is switched on in production.
 *
 * @param academyId the academy whose students may sign in this way (LUMA serves one academy per deployment)
 */
@ConfigurationProperties(prefix = "luma.student-id-login")
public record StudentIdLoginProperties(boolean enabled, UUID academyId) {
}
