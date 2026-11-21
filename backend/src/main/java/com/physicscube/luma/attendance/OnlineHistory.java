package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.ProvisionalAttendanceService.OnlineSample;
import com.physicscube.luma.zoom.ZoomParticipantService.ClassActivity;
import com.physicscube.luma.zoom.ZoomParticipantService.StudentVisit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconstructs concurrent matched students from the same visits used for attendance. */
final class OnlineHistory {
    private static final Duration MAX_WINDOW = Duration.ofHours(12);
    private static final long SAMPLE_SECONDS = 30;

    private OnlineHistory() {
    }

    private record Change(Instant at, UUID studentId, int visits) {
    }

    static List<OnlineSample> sample(ClassActivity activity, Set<UUID> visibleStudents,
                                     Instant classStart, Instant classEnd, Instant now) {
        Instant through = earlier(earlier(now, classEnd), classStart.plus(MAX_WINDOW));
        if (!activity.occurrences().isEmpty() && activity.occurrences().stream().allMatch(activity.endedAt()::containsKey)) {
            Instant lastEnd = activity.endedAt().values().stream().max(Comparator.naturalOrder()).orElseThrow();
            through = earlier(through, lastEnd);
        }

        List<Change> changes = new ArrayList<>();
        for (StudentVisit visit : activity.visits()) {
            if (!visibleStudents.contains(visit.studentId()) || visit.joinedAt() == null) {
                continue;
            }
            Instant join = visit.joinedAt().isBefore(classStart) ? classStart : visit.joinedAt();
            Instant end = earlier(activity.endedAt().getOrDefault(visit.occurrenceUuid(), classEnd), classEnd);
            Instant leave = visit.leftAt() == null ? end : earlier(visit.leftAt(), end);
            if (!join.isAfter(through) && leave.isAfter(join)) {
                changes.add(new Change(join, visit.studentId(), 1));
                changes.add(new Change(leave, visit.studentId(), -1));
            }
        }
        if (changes.isEmpty()) {
            return List.of();
        }
        changes.sort(Comparator.comparing(Change::at));

        // A student counts once even if several of their visits overlap. All changes at a timestamp take effect together.
        List<OnlineSample> samples = new ArrayList<>();
        Map<UUID, Integer> openVisits = new HashMap<>();
        int index = 0;
        Instant at = changes.getFirst().at();
        while (true) {
            while (index < changes.size() && !changes.get(index).at().isAfter(at)) {
                Change change = changes.get(index++);
                if (openVisits.merge(change.studentId(), change.visits(), Integer::sum) == 0) {
                    openVisits.remove(change.studentId());
                }
            }
            samples.add(new OnlineSample(at, openVisits.size()));
            if (at.equals(through)) {
                return List.copyOf(samples);
            }
            at = earlier(at.plusSeconds(SAMPLE_SECONDS), through);
        }
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
