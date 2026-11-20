package com.physicscube.luma.sessions;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {
    Optional<ClassSession> findByIdAndAcademyId(UUID id, UUID academyId);

    @EntityGraph(attributePaths = "batches")
    @Query("""
            select s from ClassSession s
            where s.academyId = :academyId and s.startsAt >= :from and s.startsAt < :to
            order by s.startsAt, s.id
            """)
    List<ClassSession> inWindow(@Param("academyId") UUID academyId, @Param("from") Instant from, @Param("to") Instant to);

    /** Scheduled sessions on the webinar whose time, widened by the slack, contains the instant. */
    @Query("""
            select s.id from ClassSession s
            where s.academyId = :academyId and s.zoomWebinarId = :webinarId and s.status = 'SCHEDULED'
              and s.startsAt <= :latestStart and s.endsAt >= :earliestEnd
            """)
    List<UUID> scheduledOnWebinarAround(@Param("academyId") UUID academyId, @Param("webinarId") String webinarId,
                                        @Param("latestStart") Instant latestStart, @Param("earliestEnd") Instant earliestEnd);

    @EntityGraph(attributePaths = "batches")
    @Query("""
            select distinct s from ClassSession s join s.batches b
            where s.academyId = :academyId and s.startsAt >= :from and s.startsAt < :to and b.batchId in :batchIds
            order by s.startsAt, s.id
            """)
    List<ClassSession> inWindowForBatches(@Param("academyId") UUID academyId, @Param("from") Instant from,
                                          @Param("to") Instant to, @Param("batchIds") Collection<UUID> batchIds);

    @Query("""
            select s from ClassSession s
            where s.academyId = :academyId and s.endsAt <= :now and s.status <> 'CANCELLED'
              and (:allBatches = true or exists (
                  select 1 from ClassSession visible join visible.batches b
                  where visible.id = s.id and b.batchId in :batchIds
              ))
            order by s.endsAt desc, s.id desc
            """)
    List<ClassSession> latestCompleted(@Param("academyId") UUID academyId, @Param("now") Instant now,
                                       @Param("allBatches") boolean allBatches, @Param("batchIds") Collection<UUID> batchIds,
                                       Limit limit);

    List<ClassSession> findByAcademyIdAndSeriesIdOrderByStartsAt(UUID academyId, UUID seriesId);
}
