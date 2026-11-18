package com.physicscube.luma.students;

import com.physicscube.luma.identity.AuthService;
import com.physicscube.luma.identity.AuthService.LoginOutcome;
import com.physicscube.luma.identity.InvalidCredentialsException;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Signs a student in with the Academy student ID as username and password, as the Academy asked. This is weak by
 * design (IDs are not secret); it is limited to accounts whose only role is STUDENT, is rate-limited per client like
 * every sign-in, gives the same answer for an unknown ID, a wrong password, a deactivated student and a staff
 * account, and is off unless {@code luma.student-id-login.enabled} is set.
 */
@Service
@EnableConfigurationProperties(StudentIdLoginProperties.class)
public class StudentIdLoginService {
    private static final Pattern NUMBER = Pattern.compile(Codes.PATTERN);

    private final StudentIdLoginProperties properties;
    private final StudentRepository students;
    private final AuthService auth;

    StudentIdLoginService(StudentIdLoginProperties properties, StudentRepository students, AuthService auth) {
        this.properties = properties;
        this.students = students;
        this.auth = auth;
    }

    public boolean enabled() {
        return properties.enabled() && properties.academyId() != null;
    }

    public LoginOutcome login(String studentId, String password) {
        if (!enabled()) {
            throw new ResourceNotFoundException("Route");
        }
        if (studentId == null || password == null || !NUMBER.matcher(studentId).matches()) {
            throw new InvalidCredentialsException();
        }
        String number = Codes.normalize(studentId);
        boolean matches = MessageDigest.isEqual(number.getBytes(StandardCharsets.UTF_8),
                password.strip().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        Optional<Student> student = students.findByAcademyIdAndStudentNumber(properties.academyId(), number)
                .filter(Student::isActive);
        if (!matches || student.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        return auth.studentIdLogin(student.get().getAccountId());
    }
}
