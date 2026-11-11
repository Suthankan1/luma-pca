package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(ZoomReceiptProcessingIntegrationTest.FlakyHandlerConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ZoomReceiptProcessingIntegrationTest extends StaffApiIntegrationTest {
    static final String FLAKY = "test.flaky";
    static final AtomicInteger FAILURES_LEFT = new AtomicInteger();

    /** A handler that writes, then fails while FAILURES_LEFT is positive; the error message quotes the payload. */
    @TestConfiguration
    static class FlakyHandlerConfig {
        @Bean
        ReceiptHandler flakyHandler(JdbcTemplate jdbc) {
            return new ReceiptHandler() {
                @Override
                public Set<String> eventTypes() {
                    return Set.of(FLAKY);
                }

                @Override
                public void handle(Receipt receipt) {
                    jdbc.update("INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, "
                            + "event_type, event_ts, created_at) VALUES (?, ?, ?, 'W', 'U', ?, ?, NOW())", UUID.randomUUID(),
                            receipt.academyId(), receipt.id(), receipt.eventType(), receipt.eventTs());
                    if (FAILURES_LEFT.getAndDecrement() > 0) {
                        throw new IllegalStateException("simulated outage while reading " + receipt.body());
                    }
                }
            };
        }
    }

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ZoomWebhookReceipts receipts;
    @Autowired
    DataSource dataSource;

    UUID academyId;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        FAILURES_LEFT.set(0);
    }

    private static String event(String type, long eventTs, String occurrence, String userName) {
        return "{\"event\":\"" + type + "\",\"event_ts\":" + eventTs + ",\"payload\":{\"object\":{\"id\":\"81234567890\","
                + "\"uuid\":\"" + occurrence + "\",\"participant\":{\"user_name\":\"" + userName + "\"}}}}";
    }

    private UUID store(String type, long eventTs, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertTrue(receipts.store(academyId, type, eventTs, bytes));
        return jdbc.queryForObject("SELECT id FROM zoom_webhook_receipts WHERE body_sha256 = ?", UUID.class,
                ZoomWebhookReceipts.sha256(bytes));
    }

    private Map<String, Object> receipt(UUID id) {
        return jdbc.queryForMap("SELECT * FROM zoom_webhook_receipts WHERE id = ?", id);
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE zoom_webhook_receipts SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE id = ?", id);
    }

    private int occurrenceEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_events", Integer.class);
    }

    @Test
    void eventsAreRecordedPerOccurrenceInEventTimeOrderWhateverTheArrivalOrder() {
        UUID left = store("webinar.participant_left", 1_795_000_900_000L, event("webinar.participant_left", 1_795_000_900_000L, "Occ1==", "A"));
        UUID started = store("webinar.started", 1_795_000_000_000L, event("webinar.started", 1_795_000_000_000L, "Occ1==", "A"));
        UUID joined = store("webinar.participant_joined", 1_795_000_060_000L, event("webinar.participant_joined", 1_795_000_060_000L, "Occ1==", "A"));
        store("webinar.participant_joined", 1_795_000_070_000L, event("webinar.participant_joined", 1_795_000_070_000L, "Other==", "B"));

        assertEquals(4, processor.runOnce());

        for (UUID id : List.of(left, started, joined)) {
            Map<String, Object> row = receipt(id);
            assertEquals("PROCESSED", row.get("processing_status"));
            assertEquals(1, row.get("attempts"));
            assertNotNull(row.get("processed_at"));
            assertNull(row.get("last_error"));
        }
        assertEquals(List.of("webinar.started", "webinar.participant_joined", "webinar.participant_left"),
                jdbc.queryForList("SELECT event_type FROM zoom_occurrence_events WHERE occurrence_uuid = 'Occ1==' ORDER BY event_ts",
                        String.class));
        assertEquals(Map.of("webinar_id", "81234567890", "occurrence_uuid", "Occ1==", "receipt_id", joined),
                jdbc.queryForMap("SELECT webinar_id, occurrence_uuid, receipt_id FROM zoom_occurrence_events WHERE receipt_id = ?", joined));
        assertEquals(0, processor.runOnce(), "nothing left to do");
    }

    @Test
    void reprocessingAReceiptNeverDuplicatesItsRecord() {
        UUID id = store("webinar.participant_joined", 1L, event("webinar.participant_joined", 1L, "Occ1==", "A"));
        processor.runOnce();
        jdbc.update("UPDATE zoom_webhook_receipts SET processing_status = 'RECEIVED', processed_at = NULL WHERE id = ?", id);

        assertEquals(1, processor.runOnce());

        assertEquals(1, occurrenceEvents());
        assertEquals(2, receipt(id).get("attempts"));
    }

    @Test
    void aMalformedPayloadIsDeadLetteredAtOnceWithoutQuotingIt() {
        UUID noObject = store("webinar.ended", 1L, "{\"event\":\"webinar.ended\",\"event_ts\":1,\"payload\":{}}");
        UUID noUuid = store("webinar.participant_left", 2L,
                "{\"event\":\"webinar.participant_left\",\"event_ts\":2,\"payload\":{\"object\":{\"id\":\"1\",\"participant\":{\"user_name\":\"HIDDEN-NAME\"}}}}");
        UUID longId = store("webinar.started", 3L,
                "{\"event\":\"webinar.started\",\"event_ts\":3,\"payload\":{\"object\":{\"id\":\"" + "9".repeat(41) + "\",\"uuid\":\"U\"}}}");
        UUID numericId = store("webinar.started", 4L,
                "{\"event\":\"webinar.started\",\"event_ts\":4,\"payload\":{\"object\":{\"id\":81234567890,\"uuid\":\"U\"}}}");
        UUID unknown = store("meeting.created", 5L, "{\"event\":\"meeting.created\",\"event_ts\":5}");

        processor.runOnce();

        assertEquals("Permanent: payload.object is missing", receipt(noObject).get("last_error"));
        assertEquals("Permanent: payload.object.uuid is missing or invalid", receipt(noUuid).get("last_error"));
        assertEquals("Permanent: payload.object.id is missing or invalid", receipt(longId).get("last_error"));
        assertEquals("Permanent: no handler for this event type", receipt(unknown).get("last_error"));
        for (UUID id : List.of(noObject, noUuid, longId, unknown)) {
            assertEquals("DEAD", receipt(id).get("processing_status"));
            assertEquals(1, receipt(id).get("attempts"), "permanent failures are not retried");
        }
        assertEquals("PROCESSED", receipt(numericId).get("processing_status"), "a numeric webinar ID is accepted");
        assertEquals("81234567890", jdbc.queryForObject("SELECT webinar_id FROM zoom_occurrence_events", String.class));
    }

    @Test
    void aTemporaryFailureRollsBackPartialWritesAndRetriesAfterTheBackoff(CapturedOutput output) {
        FAILURES_LEFT.set(1);
        UUID id = store(FLAKY, 1L, "{\"event\":\"test.flaky\",\"event_ts\":1,\"payload\":{\"name\":\"PRIVATE-NAME-42\"}}");
        Instant before = Instant.now();

        processor.runOnce();

        Map<String, Object> failed = receipt(id);
        assertEquals("RECEIVED", failed.get("processing_status"));
        assertEquals(1, failed.get("attempts"));
        assertEquals("IllegalStateException", failed.get("last_error"), "exception messages may quote data, so only the type is kept");
        assertEquals(0, occurrenceEvents(), "the handler's write was rolled back with the failed attempt");
        Duration wait = Duration.between(before, ((Timestamp) failed.get("next_attempt_at")).toInstant());
        assertTrue(wait.compareTo(Duration.ofSeconds(25)) > 0 && wait.compareTo(Duration.ofSeconds(35)) < 0, wait.toString());

        assertEquals(0, processor.runOnce(), "not due before the backoff ends");
        makeDue(id);
        assertEquals(1, processor.runOnce());

        assertEquals("PROCESSED", receipt(id).get("processing_status"));
        assertEquals(2, receipt(id).get("attempts"));
        assertNull(receipt(id).get("last_error"));
        assertEquals(1, occurrenceEvents());
        assertFalse(output.getAll().contains("PRIVATE-NAME-42"), "no payload content in logs");
        assertTrue(output.getAll().contains("failed attempt 1"));
    }

    @Test
    void aReceiptThatKeepsFailingIsDeadLetteredAfterTenAttemptsWithGrowingWaits() {
        FAILURES_LEFT.set(100);
        UUID id = store(FLAKY, 1L, "{\"event\":\"test.flaky\",\"event_ts\":1}");
        List<Long> waits = new ArrayList<>();
        for (int attempt = 1; attempt <= 10; attempt++) {
            makeDue(id);
            Instant before = Instant.now();
            assertEquals(1, processor.runOnce());
            Map<String, Object> row = receipt(id);
            assertEquals(attempt, row.get("attempts"));
            if (attempt < 10) {
                assertEquals("RECEIVED", row.get("processing_status"));
                waits.add(Duration.between(before, ((Timestamp) row.get("next_attempt_at")).toInstant()).toSeconds());
            }
        }
        assertEquals("DEAD", receipt(id).get("processing_status"));
        for (int i = 1; i < waits.size(); i++) {
            assertTrue(waits.get(i) >= waits.get(i - 1), waits.toString());
        }
        assertTrue(waits.get(0) >= 28 && waits.get(0) <= 31, waits.toString());
        assertTrue(waits.get(6) >= 1915 && waits.get(6) <= 1921, "seventh wait is 32 minutes: " + waits);
        assertTrue(waits.get(8) >= 3595 && waits.get(8) <= 3601, "capped at one hour: " + waits);
        makeDue(id);
        assertEquals(0, processor.runOnce(), "a dead letter is never picked up again on its own");
        assertEquals(0, occurrenceEvents());
    }

    @Test
    void severalWorkersProcessEachReceiptExactlyOnce() throws Exception {
        int total = 200;
        for (int i = 0; i < total; i++) {
            store("webinar.participant_joined", 1_000L + i, event("webinar.participant_joined", 1_000L + i, "Occ" + (i % 7), "P" + i));
        }
        int workers = 4;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Integer> handled = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, workers).<Callable<Integer>>mapToObj(w -> () -> {
                start.await();
                int count = 0;
                for (int n; (n = processor.runOnce()) > 0; ) {
                    count += n;
                }
                return count;
            }).toList())) {
                handled.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(total, handled.stream().mapToInt(Integer::intValue).sum(), handled.toString());
        assertTrue(handled.stream().filter(n -> n > 0).count() > 1, "the work was actually shared: " + handled);
        assertEquals(total, occurrenceEvents());
        assertEquals(total, jdbc.queryForObject("SELECT count(*) FROM zoom_webhook_receipts WHERE processing_status = 'PROCESSED' "
                + "AND attempts = 1", Integer.class), "no receipt was attempted twice");
    }

    @Test
    void aReceiptHeldByAnotherWorkerIsSkippedUntilReleased() throws Exception {
        UUID held = store("webinar.started", 1L, event("webinar.started", 1L, "Occ1==", "A"));
        UUID free = store("webinar.ended", 2L, event("webinar.ended", 2L, "Occ1==", "A"));
        try (Connection crashed = dataSource.getConnection()) {
            crashed.setAutoCommit(false);
            try (PreparedStatement lock = crashed.prepareStatement("SELECT id FROM zoom_webhook_receipts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery();
            }
            assertEquals(1, processor.runOnce(), "only the free receipt is taken");
            assertEquals("PROCESSED", receipt(free).get("processing_status"));
            assertEquals("RECEIVED", receipt(held).get("processing_status"));
            crashed.rollback();
        }
        assertEquals(1, processor.runOnce(), "once the other worker is gone the receipt is due again");
        assertEquals("PROCESSED", receipt(held).get("processing_status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void administratorsListDeadLettersWithoutPayloadsAndReplayThemWithAnAuditedReason() {
        FAILURES_LEFT.set(100);
        UUID id = store(FLAKY, 1L, "{\"event\":\"test.flaky\",\"event_ts\":1,\"payload\":{\"name\":\"PRIVATE-NAME-42\"}}");
        jdbc.update("UPDATE zoom_webhook_receipts SET processing_status = 'DEAD', attempts = 10, last_error = 'IllegalStateException' WHERE id = ?", id);
        String admin = tokenFor(academyId, Role.ACADEMY_ADMIN);

        HttpResponse<String> listed = api.get("/api/v1/zoom/receipts?status=DEAD", admin);

        assertEquals(200, listed.statusCode(), listed.body());
        assertFalse(listed.body().contains("PRIVATE-NAME-42"), "no payload in the operator view");
        List<Map<String, Object>> rows = HttpApi.parseList(listed).stream().map(r -> (Map<String, Object>) r).toList();
        assertEquals(List.of(id.toString()), rows.stream().map(r -> r.get("id")).toList());
        assertEquals(Set.of("id", "eventType", "eventTs", "status", "attempts", "lastError", "receivedAt", "nextAttemptAt",
                "processedAt"), rows.get(0).keySet());

        String path = "/api/v1/zoom/receipts/" + id + "/replay";
        assertEquals(400, api.post(path, HttpApi.json(Map.of("reason", "x")), admin).statusCode());
        FAILURES_LEFT.set(0);
        assertEquals(204, api.post(path, HttpApi.json(Map.of("reason", "Database restored after outage")), admin).statusCode());

        assertEquals("RECEIVED", receipt(id).get("processing_status"));
        assertEquals(0, receipt(id).get("attempts"));
        assertEquals(409, api.post(path, HttpApi.json(Map.of("reason", "again please")), admin).statusCode(), "only dead letters");
        assertEquals("{\"reason\": \"Database restored after outage\"}", jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action = 'ZOOM_RECEIPT_REPLAYED' AND target_id = ?", String.class, id));
        assertEquals(1, processor.runOnce());
        assertEquals("PROCESSED", receipt(id).get("processing_status"));
    }

    @Test
    void receiptToolsAreForOwnersAndAdministratorsOfTheSameAcademyOnly() {
        UUID id = store(FLAKY, 1L, "{\"event\":\"test.flaky\",\"event_ts\":1}");
        jdbc.update("UPDATE zoom_webhook_receipts SET processing_status = 'DEAD', attempts = 10 WHERE id = ?", id);
        String path = "/api/v1/zoom/receipts/" + id + "/replay";
        String body = HttpApi.json(Map.of("reason", "operator retry"));

        for (Role role : List.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST, Role.STUDENT)) {
            String token = tokenFor(academyId, role);
            assertEquals(403, api.get("/api/v1/zoom/receipts", token).statusCode(), role.name());
            assertEquals(403, api.post(path, body, token).statusCode(), role.name());
        }
        String outsider = tokenFor(academy("Other Academy"), Role.ACADEMY_OWNER);
        assertEquals(404, api.post(path, body, outsider).statusCode());
        assertEquals("[]", api.get("/api/v1/zoom/receipts?status=DEAD", outsider).body());
        assertEquals(401, api.get("/api/v1/zoom/receipts", null).statusCode());
        String owner = tokenFor(academyId, Role.ACADEMY_OWNER);
        assertEquals(400, api.get("/api/v1/zoom/receipts?status=GONE", owner).statusCode());
        assertEquals(400, api.get("/api/v1/zoom/receipts?limit=0", owner).statusCode());
        assertEquals(404, api.post("/api/v1/zoom/receipts/" + UUID.randomUUID() + "/replay", body, owner).statusCode());
        assertEquals("DEAD", receipt(id).get("processing_status"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }
}
