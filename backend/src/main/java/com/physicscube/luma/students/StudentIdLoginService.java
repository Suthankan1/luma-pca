package com.physicscube.luma.students;

import com.physicscube.luma.identity.AuthService;
import com.physicscube.luma.identity.InvalidCredentialsException;
import com.physicscube.luma.identity.SessionTokens;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Signs a student in with the Academy registration ID alone, as the Academy asked (owner decision 2026-11-21). This is
 * not authentication (IDs are not secret, see docs/security/STUDENT-ID-LOGIN.md); it is limited to accounts whose only
 * role is STUDENT, is rate-limited per client like every sign-in, gives the same answer for an unknown ID, a
 * deactivated student and a staff account, and is off unless {@code luma.student-id-login.enabled} is set.
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

    public SessionTokens login(String studentId) {
        if (!enabled()) {
            throw new ResourceNotFoundException("Route");
        }
        if (studentId == null || !NUMBER.matcher(studentId).matches()) {
            throw new InvalidCredentialsException();
        }
        Optional<Student> student = students.findByAcademyIdAndStudentNumber(properties.academyId(), Codes.normalize(studentId))
                .filter(Student::isActive)
                .filter(found -> found.getAccountId() != null);
        if (student.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        return auth.studentIdLogin(student.get().getAccountId());
    }
}
