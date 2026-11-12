package com.physicscube.luma.enrollments;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.AccountService;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentService.NewStudent;
import com.physicscube.luma.students.StudentView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds many students at once (for example from a spreadsheet), optionally enrolling them
 * all in one batch. Every row is checked first; if any row has a problem nothing is
 * written and every problem is reported, so the file can be fixed and sent again. A dry
 * run only checks. Accounts are created pending, exactly as for a single student; no
 * invitations are sent.
 */
@Service
public class StudentImportService {
    public static final int MAX_ROWS = 1000;
    private static final Pattern NUMBER = Pattern.compile(Codes.PATTERN);

    private final StudentService students;
    private final AccountService accounts;
    private final EnrollmentService enrollments;
    private final AuditLog audit;

    StudentImportService(StudentService students, AccountService accounts, EnrollmentService enrollments, AuditLog audit) {
        this.students = students;
        this.accounts = accounts;
        this.enrollments = enrollments;
        this.audit = audit;
    }

    public record Row(String studentNumber, String fullName, String email) {
    }

    /** @param row 1-based position in the submitted rows */
    public record Problem(int row, String field, String message) {
    }

    /** @param imported true only when the students were created; false for a dry run or when there are problems */
    public record Result(boolean imported, int rows, int enrolled, List<Problem> problems) {
    }

    @Transactional
    public Result importStudents(CurrentAccount caller, List<Row> rows, UUID batchId, LocalDate enrolledOn, boolean dryRun) {
        caller.requireAcademyManager();
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new InvalidRequestException("Send between 1 and " + MAX_ROWS + " rows");
        }
        if ((batchId == null) != (enrolledOn == null)) {
            throw new InvalidRequestException("batchId and enrolledOn go together");
        }
        if (batchId != null) {
            enrollments.requireOpenBatch(caller, batchId);
        }

        List<Problem> problems = new ArrayList<>();
        List<NewStudent> valid = new ArrayList<>(rows.size());
        Map<String, Integer> numberRow = new HashMap<>();
        Map<String, Integer> emailRow = new HashMap<>();
        Map<String, Integer> numberToCheck = new LinkedHashMap<>();
        Map<String, Integer> emailToCheck = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            int line = i + 1;
            Row row = rows.get(i) == null ? new Row(null, null, null) : rows.get(i);
            String number = null;
            if (row.studentNumber() == null || !NUMBER.matcher(row.studentNumber()).matches()) {
                problems.add(new Problem(line, "studentNumber", "Use up to 60 letters, digits, dots, dashes or underscores, starting with a letter or digit"));
            } else {
                number = Codes.normalize(row.studentNumber());
                Integer earlier = numberRow.putIfAbsent(number, line);
                if (earlier != null) {
                    problems.add(new Problem(line, "studentNumber", "Same student number as row " + earlier));
                } else {
                    numberToCheck.put(number, line);
                }
            }
            String name = row.fullName() == null ? "" : row.fullName().strip();
            if (name.isEmpty() || name.length() > 200) {
                problems.add(new Problem(line, "fullName", "A full name of up to 200 characters is required"));
            }
            String email = null;
            try {
                email = EmailAddress.normalize(row.email());
                Integer earlier = emailRow.putIfAbsent(email, line);
                if (earlier != null) {
                    problems.add(new Problem(line, "email", "Same email as row " + earlier));
                } else {
                    emailToCheck.put(email, line);
                }
            } catch (IllegalArgumentException e) {
                problems.add(new Problem(line, "email", "Email is not a valid address"));
            }
            if (number != null && email != null) {
                valid.add(new NewStudent(number, name, email));
            }
        }
        Set<String> takenNumbers = students.existingNumbers(caller.academyId(), numberToCheck.keySet());
        numberToCheck.forEach((number, line) -> {
            if (takenNumbers.contains(number)) {
                problems.add(new Problem(line, "studentNumber", "A student with this student number already exists"));
            }
        });
        Set<String> takenEmails = accounts.existingEmails(emailToCheck.keySet());
        emailToCheck.forEach((email, line) -> {
            if (takenEmails.contains(email)) {
                problems.add(new Problem(line, "email", "An account with this email already exists"));
            }
        });
        problems.sort((a, b) -> a.row() != b.row() ? Integer.compare(a.row(), b.row()) : a.field().compareTo(b.field()));

        if (!problems.isEmpty() || dryRun) {
            return new Result(false, rows.size(), 0, List.copyOf(problems));
        }
        List<StudentView> created = students.createAll(caller, valid);
        int enrolled = batchId == null ? 0
                : enrollments.enrollNew(caller, batchId, created.stream().map(StudentView::id).toList(), enrolledOn);
        // Counts and the batch only: no names, numbers or emails in the audit trail.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("students", String.valueOf(created.size()));
        details.put("enrolled", String.valueOf(enrolled));
        if (batchId != null) {
            details.put("enrolledOn", enrolledOn.toString());
        }
        audit.record(caller.academyId(), caller.accountId(), "STUDENTS_IMPORTED", batchId == null ? "ACADEMY" : "BATCH",
                batchId == null ? caller.academyId() : batchId, details);
        return new Result(true, rows.size(), enrolled, List.of());
    }
}
