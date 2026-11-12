package com.physicscube.luma.students;

import com.physicscube.luma.identity.AccountService;
import com.physicscube.luma.identity.AccountSummary;
import com.physicscube.luma.identity.DuplicateEmailException;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student records of the caller's academy. Staff routes are limited to academy
 * owners and administrators until scoped staff assignments exist; a student may
 * read only their own record. A student of another academy is reported as not found.
 */
@Service
public class StudentService {
    public static final int MAX_PAGE_SIZE = 200;

    private final StudentRepository students;
    private final AccountService accounts;

    StudentService(StudentRepository students, AccountService accounts) {
        this.students = students;
        this.accounts = accounts;
    }

    /** @param active {@code null} for all; @param after last student number of the previous page, or {@code null} */
    @Transactional(readOnly = true)
    public StudentPage list(CurrentAccount caller, Boolean active, String after, int limit) {
        caller.requireAcademyManager();
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<Student> found = students.page(caller.academyId(), after == null ? "" : Codes.normalize(after), active,
                Limit.of(limit + 1));
        boolean more = found.size() > limit;
        List<Student> page = more ? found.subList(0, limit) : found;
        return new StudentPage(views(page), more ? page.get(page.size() - 1).getStudentNumber() : null);
    }

    @Transactional(readOnly = true)
    public StudentView get(CurrentAccount caller, UUID studentId) {
        caller.requireAcademyManager();
        return view(load(caller.academyId(), studentId));
    }

    /** The calling student's own record. */
    @Transactional(readOnly = true)
    public StudentView mine(CurrentAccount caller) {
        caller.requireRole(Role.STUDENT.name());
        return view(students.findByAccountIdAndAcademyId(caller.accountId(), caller.academyId())
                .orElseThrow(() -> new ResourceNotFoundException("Student record")));
    }

    /**
     * Creates the student and a sign-in account (STUDENT role, pending activation) in
     * one transaction: either both exist afterwards or neither does.
     */
    @Transactional
    public StudentView create(CurrentAccount caller, String studentNumber, String fullName, String email) {
        caller.requireAcademyManager();
        String number = Codes.normalize(studentNumber);
        if (students.existsByAcademyIdAndStudentNumber(caller.academyId(), number)) {
            throw duplicateNumber();
        }
        AccountSummary account;
        try {
            account = accounts.register(caller.academyId(), email, Set.of(Role.STUDENT));
        } catch (DuplicateEmailException e) {
            throw new ConflictException("An account with this email already exists");
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Email is not a valid address");
        }
        try {
            return StudentView.of(students.saveAndFlush(new Student(caller.academyId(), account.id(), number, fullName.strip())),
                    account);
        } catch (DataIntegrityViolationException e) {
            throw String.valueOf(e.getMessage()).contains("uq_students_academy_number") ? duplicateNumber() : e;
        }
    }

    /** Of the normalised student numbers, those already used in the academy. */
    @Transactional(readOnly = true)
    public Set<String> existingNumbers(UUID academyId, Collection<String> normalizedNumbers) {
        return normalizedNumbers.isEmpty() ? Set.of() : Set.copyOf(students.existingNumbers(academyId, normalizedNumbers));
    }

    /** A validated row for {@link #createAll}: normalised number, stripped name, raw email. */
    public record NewStudent(String studentNumber, String fullName, String email) {
    }

    /**
     * Creates students and their pending sign-in accounts in batches, all or nothing. The
     * caller has validated the rows and checked numbers and emails are new and distinct;
     * a clash with a concurrent change is reported as a conflict and rolls everything back.
     */
    @Transactional
    public List<StudentView> createAll(CurrentAccount caller, List<NewStudent> rows) {
        caller.requireAcademyManager();
        List<AccountSummary> created;
        try {
            created = accounts.registerAll(caller.academyId(), rows.stream().map(NewStudent::email).toList(), Set.of(Role.STUDENT));
        } catch (DuplicateEmailException e) {
            throw new ConflictException("An email in the file was used by another account meanwhile; check again and retry");
        }
        List<Student> made = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            made.add(new Student(caller.academyId(), created.get(i).id(), rows.get(i).studentNumber(), rows.get(i).fullName()));
        }
        try {
            List<Student> saved = students.saveAllAndFlush(made);
            List<StudentView> views = new java.util.ArrayList<>(saved.size());
            for (int i = 0; i < saved.size(); i++) {
                views.add(StudentView.of(saved.get(i), created.get(i)));
            }
            return views;
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("uq_students_academy_number")) {
                throw new ConflictException("A student number in the file was used meanwhile; check again and retry");
            }
            throw e;
        }
    }

    /** The student number is the academy's stable identifier and cannot be changed. */
    @Transactional
    public StudentView update(CurrentAccount caller, UUID studentId, String fullName, boolean active, long expectedVersion) {
        caller.requireAcademyManager();
        Student student = load(caller.academyId(), studentId);
        if (student.getVersion() != expectedVersion) {
            throw new ConflictException("The record was changed by someone else; reload and retry");
        }
        student.update(fullName.strip(), active);
        return view(students.saveAndFlush(student));
    }

    /** For other modules: the student if it belongs to the academy, else not found. No role check. */
    @Transactional(readOnly = true)
    public StudentView requireInAcademy(UUID academyId, UUID studentId) {
        return view(load(academyId, studentId));
    }

    /** For other modules: the caller's own student id, if their account has a student record. No role check. */
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> idForAccount(UUID academyId, UUID accountId) {
        return students.findByAccountIdAndAcademyId(accountId, academyId).map(Student::getId);
    }

    /** For other modules: views of those students that belong to the academy. No role check. */
    @Transactional(readOnly = true)
    public Map<UUID, StudentView> viewsInAcademy(UUID academyId, Collection<UUID> studentIds) {
        return views(students.findByAcademyIdAndIdIn(academyId, studentIds)).stream()
                .collect(Collectors.toMap(StudentView::id, Function.identity()));
    }

    private Student load(UUID academyId, UUID studentId) {
        return students.findByIdAndAcademyId(studentId, academyId).orElseThrow(() -> new ResourceNotFoundException("Student"));
    }

    private StudentView view(Student student) {
        return StudentView.of(student, accounts.findById(student.getAccountId()).orElse(null));
    }

    private List<StudentView> views(List<Student> page) {
        Map<UUID, AccountSummary> linked = accounts.findByIds(page.stream().map(Student::getAccountId).toList());
        return page.stream().map(student -> StudentView.of(student, linked.get(student.getAccountId()))).toList();
    }

    private static ConflictException duplicateNumber() {
        return new ConflictException("A student with this student number already exists");
    }
}
