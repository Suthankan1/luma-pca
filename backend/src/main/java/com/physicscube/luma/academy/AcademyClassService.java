package com.physicscube.luma.academy;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.batches.BatchView;
import com.physicscube.luma.enrollments.EnrollmentService;
import com.physicscube.luma.enrollments.EnrollmentView;
import com.physicscube.luma.identity.AccountService;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.api.ServiceUnavailableException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentService.NewStudent;
import com.physicscube.luma.students.StudentView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connects the Academy's own classes (read-only, from its payment rows) to LUMA batches, so the existing class
 * sessions, Zoom matching and attendance work unchanged. A sync brings a linked batch's enrolments in step with the
 * Academy's class: missing students are created (by their Academy ID, with the Academy's name and email when
 * available) and enrolled. Nobody is withdrawn automatically: students who left the Academy's class, and students
 * staff withdrew in LUMA, are reported for staff to decide.
 */
@Service
public class AcademyClassService {
    static final int MAX_LISTED = 50;
    static final String SYNTHETIC_DOMAIN = "students.academy.invalid";
    private static final ZoneId ACADEMY_ZONE = ZoneId.of("Asia/Colombo");
    private static final Pattern NUMBER = Pattern.compile(Codes.PATTERN);

    private final ObjectProvider<AcademyPaymentsCatalog> catalog;
    private final JdbcTemplate jdbc;
    private final BatchService batches;
    private final StudentService students;
    private final EnrollmentService enrollments;
    private final AccountService accounts;
    private final AuditLog audit;

    AcademyClassService(ObjectProvider<AcademyPaymentsCatalog> catalog, JdbcTemplate jdbc, BatchService batches,
                        StudentService students, EnrollmentService enrollments, AccountService accounts, AuditLog audit) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.batches = batches;
        this.students = students;
        this.enrollments = enrollments;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** @param batch null until the class is linked */
    public record AcademyClassView(String className, long studentCount, LinkedBatch batch) {
    }

    public record LinkedBatch(UUID id, UUID courseId, String code, String name, boolean active, Instant lastSyncedAt,
                              Integer lastSyncedStudents) {
    }

    /**
     * @param created           students new to LUMA, created and enrolled
     * @param enrolled          students enrolled in the batch by this sync (created ones included)
     * @param alreadyEnrolled   students already enrolled
     * @param withoutName       students the Academy has no single name for (shown by Academy ID)
     * @param withoutEmail      new students without a usable Academy email (Zoom cannot match them automatically)
     * @param withdrawnInLuma   Academy IDs of students staff withdrew from the batch in LUMA (left withdrawn)
     * @param inactiveInLuma    Academy IDs of students deactivated in LUMA (not enrolled)
     * @param invalidIds        Academy IDs LUMA cannot use as a student number (not imported)
     * @param notInAcademyClass Academy IDs enrolled in the batch who are no longer in the Academy's class (left enrolled)
     */
    public record SyncResult(int academyStudents, int created, int enrolled, int alreadyEnrolled, int withoutName,
                             int withoutEmail, List<String> withdrawnInLuma, List<String> inactiveInLuma,
                             List<String> invalidIds, List<String> notInAcademyClass) {
    }

    private record Link(UUID id, String className, UUID batchId, Instant lastSyncedAt, Integer lastSyncedStudents) {
    }

    /** Every Academy class with its distinct-student count and, when linked, its LUMA batch. Managers only. */
    @Transactional(readOnly = true)
    public List<AcademyClassView> classes(CurrentAccount caller) {
        caller.requireAcademyManager();
        List<AcademyPaymentClass> source = read(AcademyPaymentsCatalog::listClasses);
        Map<String, Link> links = links(caller.academyId());
        Map<UUID, BatchView> linkedBatches = batches.viewsInAcademy(caller.academyId(),
                links.values().stream().map(Link::batchId).toList());
        List<AcademyClassView> views = new ArrayList<>(source.size());
        for (AcademyPaymentClass item : source) {
            Link link = links.get(item.className());
            BatchView batch = link == null ? null : linkedBatches.get(link.batchId());
            views.add(new AcademyClassView(item.className(), item.studentCount(), batch == null ? null
                    : new LinkedBatch(batch.id(), batch.courseId(), batch.code(), batch.name(), batch.active(),
                    link.lastSyncedAt(), link.lastSyncedStudents())));
        }
        return views;
    }

    /** Links (or re-links) an Academy class to an active batch. A batch stands for at most one Academy class. */
    @Transactional
    public AcademyClassView link(CurrentAccount caller, String className, UUID batchId) {
        caller.requireAcademyManager();
        String name = requireName(className);
        if (batchId == null) {
            throw new InvalidRequestException("batchId is required");
        }
        AcademyPaymentClass source = read(c -> c.listClasses()).stream().filter(c -> c.className().equals(name)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Academy class"));
        BatchView batch = enrollments.requireOpenBatch(caller, batchId);
        Link existing = links(caller.academyId()).get(name);
        try {
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO academy_class_links (id, academy_id, class_name, batch_id, linked_by)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), caller.academyId(), name, batchId, caller.accountId());
            } else if (!existing.batchId().equals(batchId)) {
                jdbc.update("""
                        UPDATE academy_class_links SET batch_id = ?, linked_by = ?, linked_at = now(),
                               last_synced_at = NULL, last_synced_by = NULL, last_synced_students = NULL
                        WHERE id = ?
                        """, batchId, caller.accountId(), existing.id());
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ConflictException("That batch is already linked to another Academy class");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("className", name);
        audit.record(caller.academyId(), caller.accountId(), "ACADEMY_CLASS_LINKED", "BATCH", batchId, details);
        Link now = links(caller.academyId()).get(name);
        return new AcademyClassView(name, source.studentCount(), new LinkedBatch(batch.id(), batch.courseId(), batch.code(),
                batch.name(), batch.active(), now.lastSyncedAt(), now.lastSyncedStudents()));
    }

    /** Brings the linked batch's enrolments in step with the Academy class. Safe to repeat. */
    @Transactional
    public SyncResult sync(CurrentAccount caller, String className) {
        caller.requireAcademyManager();
        String name = requireName(className);
        Link link = links(caller.academyId()).get(name);
        if (link == null) {
            throw new ResourceNotFoundException("Link for this Academy class");
        }
        enrollments.requireOpenBatch(caller, link.batchId());
        List<AcademyStudent> roster = read(c -> c.rosterForClass(name));

        List<String> invalid = new ArrayList<>();
        Map<String, AcademyStudent> byNumber = new LinkedHashMap<>();
        for (AcademyStudent student : roster) {
            if (student.studentId() == null || !NUMBER.matcher(student.studentId()).matches()) {
                invalid.add(student.studentId());
            } else {
                byNumber.putIfAbsent(Codes.normalize(student.studentId()), student);
            }
        }
        int withoutName = (int) byNumber.values().stream().filter(s -> s.name() == null).count();

        Map<String, UUID> known = students.idsByNumbers(caller.academyId(), byNumber.keySet());
        List<String> newNumbers = byNumber.keySet().stream().filter(n -> !known.containsKey(n)).toList();
        List<NewStudent> rows = newRows(newNumbers, byNumber);
        int withoutEmail = (int) rows.stream().filter(r -> r.email().endsWith("@" + SYNTHETIC_DOMAIN)).count();
        List<StudentView> created = rows.isEmpty() ? List.of() : students.createAll(caller, rows);

        Map<UUID, EnrollmentView> current = new HashMap<>();
        for (EnrollmentView e : enrollments.roster(caller, link.batchId())) {
            current.put(e.studentId(), e);
        }
        Map<UUID, StudentView> existing = students.viewsInAcademy(caller.academyId(), known.values());
        List<UUID> toEnroll = new ArrayList<>(created.stream().map(StudentView::id).toList());
        List<String> withdrawn = new ArrayList<>();
        List<String> inactive = new ArrayList<>();
        int alreadyEnrolled = 0;
        for (Map.Entry<String, UUID> entry : known.entrySet()) {
            EnrollmentView enrollment = current.get(entry.getValue());
            StudentView student = existing.get(entry.getValue());
            if (enrollment != null) {
                if ("WITHDRAWN".equals(enrollment.status())) {
                    withdrawn.add(entry.getKey());
                } else {
                    alreadyEnrolled++;
                }
            } else if (student == null || !student.active()) {
                inactive.add(entry.getKey());
            } else {
                toEnroll.add(entry.getValue());
            }
        }
        LocalDate today = LocalDate.now(ACADEMY_ZONE);
        int enrolled = toEnroll.isEmpty() ? 0 : enrollments.enrollNew(caller, link.batchId(), toEnroll, today);

        List<String> notInClass = current.values().stream()
                .filter(e -> !"WITHDRAWN".equals(e.status()) && !byNumber.containsKey(e.studentNumber()))
                .map(EnrollmentView::studentNumber).sorted().toList();

        jdbc.update("""
                UPDATE academy_class_links SET last_synced_at = now(), last_synced_by = ?, last_synced_students = ?
                WHERE id = ?
                """, caller.accountId(), byNumber.size(), link.id());
        // Counts only: no names, IDs or emails in the audit trail.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("className", name);
        details.put("academyStudents", String.valueOf(byNumber.size()));
        details.put("created", String.valueOf(created.size()));
        details.put("enrolled", String.valueOf(enrolled));
        details.put("notInAcademyClass", String.valueOf(notInClass.size()));
        audit.record(caller.academyId(), caller.accountId(), "ACADEMY_CLASS_SYNCED", "BATCH", link.batchId(), details);
        return new SyncResult(byNumber.size(), created.size(), enrolled, alreadyEnrolled, withoutName, withoutEmail,
                cap(withdrawn), cap(inactive), cap(invalid), cap(notInClass));
    }

    /**
     * New students' rows. The Academy email becomes the LUMA sign-in email only when it is valid, used by no other
     * LUMA account and by no other student in this roster (siblings often share one); otherwise a placeholder that
     * can never receive mail, and staff resolve the student's Zoom identity by hand.
     */
    private List<NewStudent> newRows(List<String> numbers, Map<String, AcademyStudent> byNumber) {
        Map<String, String> email = new HashMap<>();
        Map<String, Integer> uses = new HashMap<>();
        for (String number : numbers) {
            try {
                String normalized = EmailAddress.normalize(byNumber.get(number).email());
                email.put(number, normalized);
                uses.merge(normalized, 1, Integer::sum);
            } catch (IllegalArgumentException ignored) {
                // no usable email
            }
        }
        Set<String> taken = new HashSet<>(accounts.existingEmails(Set.copyOf(email.values())));
        List<String> placeholders = new ArrayList<>();
        for (String number : numbers) {
            placeholders.add(placeholder(number));
        }
        taken.addAll(accounts.existingEmails(Set.copyOf(placeholders)));
        List<NewStudent> rows = new ArrayList<>(numbers.size());
        for (String number : numbers) {
            String chosen = email.get(number);
            if (chosen == null || uses.get(chosen) > 1 || taken.contains(chosen)) {
                chosen = placeholder(number);
                for (int i = 2; taken.contains(chosen); i++) {
                    chosen = placeholder(number + "-" + i);
                }
            }
            taken.add(chosen);
            String name = byNumber.get(number).name();
            rows.add(new NewStudent(number, name == null ? number : truncate(name), chosen));
        }
        return rows;
    }

    static String placeholder(String number) {
        return number.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-") + "@" + SYNTHETIC_DOMAIN;
    }

    private static String truncate(String name) {
        return name.length() <= 200 ? name : name.substring(0, 200).strip();
    }

    private static List<String> cap(List<String> values) {
        return List.copyOf(values.size() <= MAX_LISTED ? values : values.subList(0, MAX_LISTED));
    }

    private static String requireName(String className) {
        if (className == null || className.isBlank() || className.strip().length() > 200) {
            throw new InvalidRequestException("className is required (up to 200 characters)");
        }
        return className.strip();
    }

    private Map<String, Link> links(UUID academyId) {
        Map<String, Link> links = new HashMap<>();
        jdbc.query("""
                SELECT id, class_name, batch_id, last_synced_at, last_synced_students
                FROM academy_class_links WHERE academy_id = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> links.put(row.getString("class_name"),
                new Link((UUID) row.getObject("id"), row.getString("class_name"), (UUID) row.getObject("batch_id"),
                        row.getTimestamp("last_synced_at") == null ? null : row.getTimestamp("last_synced_at").toInstant(),
                        (Integer) row.getObject("last_synced_students"))), academyId);
        return links;
    }

    private <T> T read(java.util.function.Function<AcademyPaymentsCatalog, T> query) {
        AcademyPaymentsCatalog source = catalog.getIfAvailable();
        if (source == null) {
            throw new ServiceUnavailableException("The Academy database is not connected");
        }
        try {
            return query.apply(source);
        } catch (DataAccessException e) {
            // The cause can name hosts or roles: keep it out of the response.
            org.slf4j.LoggerFactory.getLogger(AcademyClassService.class)
                    .warn("Academy database query failed: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("The Academy database could not be reached; try again shortly");
        }
    }
}
