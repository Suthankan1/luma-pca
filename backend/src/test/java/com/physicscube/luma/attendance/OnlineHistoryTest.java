package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.attendance.ProvisionalAttendanceService.OnlineSample;
import com.physicscube.luma.zoom.ZoomParticipantService.ClassActivity;
import com.physicscube.luma.zoom.ZoomParticipantService.StudentVisit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OnlineHistoryTest {
    private final UUID student = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-11-20T07:00:00Z");

    private ClassActivity activity(StudentVisit... visits) {
        return new ClassActivity(List.of("run"), Map.of("run", start), Map.of(), List.of(visits), 0, Set.of());
    }

    @Test
    void lastSampleUsesTheCurrentTimeAndCountsAnOpenRejoin() {
        Instant first = start.plusSeconds(10);
        Instant now = start.plusSeconds(85);
        ClassActivity activity = activity(new StudentVisit(student, "run", first, start.plusSeconds(45)),
                new StudentVisit(student, "run", start.plusSeconds(80), null));

        assertEquals(List.of(new OnlineSample(first, 1), new OnlineSample(start.plusSeconds(40), 1),
                new OnlineSample(start.plusSeconds(70), 0), new OnlineSample(now, 1)),
                OnlineHistory.sample(activity, Set.of(student), start, start.plusSeconds(3600), now));
    }

    @Test
    void historyIsBoundedToTwelveHoursOfSamples() {
        Instant cap = start.plusSeconds(12 * 3600);
        Instant now = cap.plusSeconds(3600);
        List<OnlineSample> samples = OnlineHistory.sample(activity(new StudentVisit(student, "run", start, null)),
                Set.of(student), start, now.plusSeconds(1), now);

        assertEquals(1441, samples.size());
        assertEquals(new OnlineSample(cap, 1), samples.getLast());
    }
}
