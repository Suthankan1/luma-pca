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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    StudentService(StudentRepository students, AccountService accounts, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.students = students;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public StudentPage list(CurrentAccount caller, Boolean active, String after, int limit) {
        return list(caller, active, after, limit, null);
    }

    /**
     * @param active {@code null} for all; @param after last student number of the previous page, or {@code null}
     * @param query {@code null} for everyone; otherwise part of a student number or name (any letter case),
     *              or a whole email address
     */
    @Transactional(readOnly = true)
    public StudentPage list(CurrentAccount caller, Boolean active, String after, int limit, String query) {
        caller.requireAcademyManager();
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        String start = after == null ? "" : Codes.normalize(after);
        String term = query == null ? "" : query.strip();
        if (term.length() > 100) {
            throw new InvalidRequestException("q must be at most 100 characters");
        }
        List<Student> found;
        if (term.isEmpty()) {
            found = students.page(caller.academyId(), start, active, Limit.of(limit + 1));
        } else {
            String lower = term.toLowerCase(java.util.Locale.ROOT);
            String pattern = "%" + lower.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            // An email matches only as a whole address, so a search cannot be used to guess addresses letter by letter.
            UUID accountId = lower.contains("@") ? accounts.findByEmailIfValid(lower).map(AccountSummary::id).orElse(null) : null;
            found = students.search(caller.academyId(), start, active, pattern, accountId, Limit.of(limit + 1));
        }
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
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        // One round trip: student and sign-in account together (the database may be far away).
        return joinedViews("s.id = ANY (?)", academyId, studentIds.stream().distinct().toArray(UUID[]::new)).stream()
                .collect(Collectors.toMap(StudentView::id, Function.identity()));
    }

    /** For other modules: the academy's students holding these sign-in accounts, by account id. No role check. */
    @Transactional(readOnly = true)
    public Map<UUID, StudentView> byAccountIds(UUID academyId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return views(students.findByAcademyIdAndAccountIdIn(academyId, accountIds)).stream()
                .collect(Collectors.toMap(StudentView::accountId, Function.identity()));
    }

    /** For other modules: the academy's students whose sign-in email is one of these normalised emails, by email. No role check. */
    @Transactional(readOnly = true)
    public Map<String, StudentView> bySignInEmails(UUID academyId, Collection<String> normalizedEmails) {
        if (normalizedEmails.isEmpty()) {
            return Map.of();
        }
        return joinedViews("a.email = ANY (?)", academyId, normalizedEmails.stream().distinct().toArray(String[]::new)).stream()
                .collect(Collectors.toMap(StudentView::email, Function.identity()));
    }

    /** Students of the academy with their sign-in account's email and status, in one query; {@code where} filters them. */
    private List<StudentView> joinedViews(String where, UUID academyId, Object[] values) {
        return jdbc.query("""
                SELECT s.id, s.student_number, s.full_name, a.email, s.account_id, a.status, s.active, s.version,
                       s.created_at, s.updated_at
                FROM students s JOIN accounts a ON a.id = s.account_id AND a.academy_id = s.academy_id
                WHERE s.academy_id = ? AND\s""" + where, (row, i) -> new StudentView((UUID) row.getObject("id"),
                row.getString("student_number"), row.getString("full_name"), row.getString("email"), (UUID) row.getObject("account_id"),
                row.getString("status"), row.getBoolean("active"), row.getLong("version"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()), academyId, values);
    }

    /** For other modules: ids of the academy's students by normalised student number, for the numbers in use. No role check. */
    @Transactional(readOnly = true)
    public Map<String, UUID> idsByNumbers(UUID academyId, Collection<String> normalizedNumbers) {
        if (normalizedNumbers.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> ids = new java.util.HashMap<>();
        jdbc.query("SELECT student_number, id FROM students WHERE academy_id = ? AND student_number = ANY (?)",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> ids.put(row.getString("student_number"),
                        (UUID) row.getObject("id")),
                academyId, normalizedNumbers.toArray(String[]::new));
        return ids;
    }

    /** For other modules: sign-in account ids of the academy's active students, or of those among {@code studentIds}. No role check. */
    @Transactional(readOnly = true)
    public List<UUID> activeAccountIds(UUID academyId, Collection<UUID> studentIds) {
        if (studentIds == null) {
            return students.activeAccountIds(academyId);
        }
        return studentIds.isEmpty() ? List.of() : students.activeAccountIds(academyId, studentIds);
    }

    private Student load(UUID academyId, UUID studentId) {
        return students.findByIdAndAcademyId(studentId, academyId).orElseThrow(() -> new ResourceNotFoundException("Student"));
    }

    private StudentView view(Student student) {
        return StudentView.of(student, accounts.findById(student.getAccountId()).orElse(null));
    }

    private List<StudentView> views(List<Student> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        // Only the email and status are shown, so one plain query instead of loading each account with its roles.
        Map<UUID, String[]> linked = new java.util.HashMap<>();
        jdbc.query("SELECT id, email, status FROM accounts WHERE id = ANY (?)",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> linked.put((UUID) row.getObject("id"),
                        new String[] {row.getString("email"), row.getString("status")}),
                (Object) page.stream().map(Student::getAccountId).distinct().toArray(UUID[]::new));
        return page.stream().map(student -> {
            String[] account = linked.get(student.getAccountId());
            return new StudentView(student.getId(), student.getStudentNumber(), student.getFullName(), account == null ? null : account[0],
                    student.getAccountId(), account == null ? null : account[1], student.isActive(), student.getVersion(),
                    student.getCreatedAt(), student.getUpdatedAt());
        }).toList();
    }

    private static ConflictException duplicateNumber() {
        return new ConflictException("A student with this student number already exists");
    }
}
