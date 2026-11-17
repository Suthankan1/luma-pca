package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.zoom.api.ZoomApiClient;
import com.physicscube.luma.zoom.api.ZoomApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The academy's upcoming webinars, read from Zoom, so staff turn them into classes in one step instead of typing each
 * date and webinar ID. Every run of a recurring webinar is listed; runs that already have a class are marked. Importing
 * re-reads the webinar from Zoom (nothing from the browser is trusted for times) and creates an ordinary class per run,
 * so linking, attendance and finalisation work exactly as for classes scheduled by hand. Owners and administrators only.
 */
@Service
public class ZoomImportService {
    /** A class counts as the same run when it starts within this of Zoom's start time. */
    static final Duration SAME_RUN = Duration.ofMinutes(30);
    static final Duration HORIZON = Duration.ofDays(120);
    static final int MAX_RUNS = 300;

    private final ObjectProvider<ZoomApiClient> api;
    private final ClassSessionService sessions;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final List<String> hosts;
    private final List<String> allowList;

    ZoomImportService(ObjectProvider<ZoomApiClient> api, ClassSessionService sessions, JdbcTemplate jdbc, AuditLog audit,
                      @Value("${luma.zoom.import.hosts:me}") String hosts, ZoomWebhookProperties webhook) {
        this.allowList = webhook.webinarAllowList();
        this.api = api;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
        this.hosts = Arrays.stream(hosts.split(",")).map(String::strip).filter(h -> !h.isEmpty()).distinct().toList();
    }

    /** @param classId the existing class for this run, or {@code null} */
    public record Run(String occurrenceId, Instant startsAt, Instant endsAt, UUID classId) {
    }

    /**
     * @param note       why a webinar has no runs to import (e.g. a recurring webinar with no fixed time)
     * @param liveEvents false when the webhook allow-list leaves this webinar out: attendance then comes from Zoom's report only
     */
    public record UpcomingWebinar(String webinarId, String topic, String timeZone, boolean recurring, List<Run> runs, String note,
                                  boolean liveEvents) {
    }

    public record ImportResult(String webinarId, int created, int alreadyScheduled, List<UUID> classIds) {
    }

    private ZoomApiClient client() {
        ZoomApiClient client = api.getIfAvailable();
        if (client == null) {
            throw new ConflictException("The Zoom API is not configured for this academy");
        }
        return client;
    }

    /** Upcoming webinars of the configured Zoom hosts, soonest first, each with its runs in the next 120 days. */
    public List<UpcomingWebinar> upcoming(CurrentAccount caller) {
        caller.requireAcademyManager();
        ZoomApiClient client = client();
        Map<Long, ZoomApiClient.ListedWebinar> listed = new LinkedHashMap<>();
        try {
            for (String host : hosts) {
                client.upcomingWebinars(host).forEach(w -> listed.putIfAbsent(w.id(), w));
            }
        } catch (ZoomApiException e) {
            throw new ConflictException(explain(e));
        }
        Instant now = DbTime.now();
        List<UpcomingWebinar> result = new ArrayList<>();
        int runs = 0;
        for (ZoomApiClient.ListedWebinar w : listed.values()) {
            if (runs >= MAX_RUNS) {
                break;
            }
            UpcomingWebinar view = view(caller.academyId(), client, String.valueOf(w.id()), now);
            runs += view.runs().size();
            result.add(view);
        }
        result.sort(Comparator.comparing((UpcomingWebinar u) -> u.runs().isEmpty() ? Instant.MAX : u.runs().get(0).startsAt()));
        return result;
    }

    /** Creates a class for each chosen upcoming run (all when {@code occurrenceIds} is empty) that has none yet; all or nothing. */
    @org.springframework.transaction.annotation.Transactional
    public ImportResult importRuns(CurrentAccount caller, String webinarId, List<UUID> batchIds, List<String> occurrenceIds, String title) {
        caller.requireAcademyManager();
        if (webinarId == null || !webinarId.replaceAll("[\\s-]", "").matches("[0-9]{9,20}")) {
            throw new InvalidRequestException("webinarId must be a Zoom webinar ID");
        }
        if (batchIds == null || batchIds.isEmpty()) {
            throw new InvalidRequestException("Choose at least one batch");
        }
        String id = webinarId.replaceAll("[\\s-]", "");
        ZoomApiClient client = client();
        UpcomingWebinar webinar = view(caller.academyId(), client, id, DbTime.now());
        Set<String> wanted = occurrenceIds == null ? Set.of() : new LinkedHashSet<>(occurrenceIds);
        String name = title == null || title.isBlank() ? webinar.topic() : title.strip();
        if (name == null || name.isBlank()) {
            name = "Webinar " + id;
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        List<UUID> created = new ArrayList<>();
        int existing = 0;
        for (Run run : webinar.runs()) {
            if (!wanted.isEmpty() && !wanted.contains(run.occurrenceId())) {
                continue;
            }
            if (run.classId() != null) {
                existing++;
                continue;
            }
            created.add(sessions.create(caller, name, run.startsAt(), run.endsAt(), webinar.timeZone(), batchIds, id).id());
        }
        if (created.isEmpty() && existing == 0) {
            throw new InvalidRequestException(webinar.note() != null ? webinar.note() : "None of the chosen runs is upcoming");
        }
        audit.record(caller.academyId(), caller.accountId(), "ZOOM_WEBINAR_IMPORTED", "ZOOM_WEBINAR", null, Map.of(
                "webinar", id, "created", String.valueOf(created.size()), "alreadyScheduled", String.valueOf(existing),
                "batches", String.valueOf(batchIds.size())));
        return new ImportResult(id, created.size(), existing, created);
    }

    private UpcomingWebinar view(UUID academyId, ZoomApiClient client, String webinarId, Instant now) {
        ZoomApiClient.Webinar w;
        try {
            w = client.webinar(webinarId);
        } catch (ZoomApiException e) {
            if (e.status() == 404) {
                throw new ResourceNotFoundException("Zoom webinar");
            }
            throw new ConflictException(explain(e));
        }
        String zone = validZone(w.timezone());
        boolean recurring = w.type() == 6 || w.type() == 9;
        List<Instant[]> times = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (recurring) {
            for (ZoomApiClient.Occurrence o : w.occurrences()) {
                if (o.startTime() != null && !"deleted".equalsIgnoreCase(o.status())) {
                    int minutes = o.duration() > 0 ? o.duration() : w.duration();
                    times.add(new Instant[] {o.startTime(), o.startTime().plus(Duration.ofMinutes(Math.max(minutes, 1)))});
                    ids.add(o.occurrenceId());
                }
            }
        } else if (w.startTime() != null) {
            times.add(new Instant[] {w.startTime(), w.startTime().plus(Duration.ofMinutes(Math.max(w.duration(), 1)))});
            ids.add(String.valueOf(w.id()));
        }
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            Instant start = times.get(i)[0];
            Instant end = times.get(i)[1];
            if (!end.isAfter(now) || start.isAfter(now.plus(HORIZON))) {
                continue;
            }
            runs.add(new Run(ids.get(i), start, end, existingClass(academyId, webinarId, start)));
        }
        runs.sort(Comparator.comparing(Run::startsAt));
        String note = !runs.isEmpty() ? null : w.type() == 6 ? "A recurring webinar with no fixed time has no dates to import; give it a "
                + "fixed schedule in Zoom, or schedule its classes in LUMA with its webinar ID" : "No upcoming runs in the next 120 days";
        return new UpcomingWebinar(String.valueOf(w.id()), w.topic(), zone, recurring, runs, note,
                allowList.isEmpty() || allowList.contains(String.valueOf(w.id())));
    }

    private UUID existingClass(UUID academyId, String webinarId, Instant start) {
        List<UUID> found = jdbc.queryForList("""
                SELECT id FROM class_sessions WHERE academy_id = ? AND zoom_webinar_id = ? AND status = 'SCHEDULED'
                  AND starts_at BETWEEN ? AND ? ORDER BY abs(extract(epoch FROM starts_at - ?)) LIMIT 1
                """, UUID.class, academyId, webinarId, Timestamp.from(start.minus(SAME_RUN)), Timestamp.from(start.plus(SAME_RUN)),
                Timestamp.from(start));
        return found.isEmpty() ? null : found.get(0);
    }

    private static String validZone(String zone) {
        try {
            return zone == null || zone.isBlank() || ZoneId.of(zone) instanceof java.time.ZoneOffset ? null : zone;
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static String explain(ZoomApiException e) {
        if (e.status() == 400 || e.status() == 401 || e.status() == 403 || e.status() == 404) {
            return "Zoom refused to list the academy's webinars (HTTP " + e.status() + "). The Zoom app needs the scope "
                    + "webinar:read:list_webinars:admin, and luma.zoom.import.hosts must name the webinar host (email or user ID)";
        }
        return "Zoom could not be reached right now; try again in a minute";
    }
}
