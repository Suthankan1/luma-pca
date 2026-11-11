package com.physicscube.luma.sessions;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {
    Optional<ClassSession> findByIdAndAcademyId(UUID id, UUID academyId);

    @Query("""
            select s from ClassSession s
            where s.academyId = :academyId and s.startsAt >= :from and s.startsAt < :to
            order by s.startsAt, s.id
            """)
    List<ClassSession> inWindow(@Param("academyId") UUID academyId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select distinct s from ClassSession s join s.batches b
            where s.academyId = :academyId and s.startsAt >= :from and s.startsAt < :to and b.batchId in :batchIds
            order by s.startsAt, s.id
            """)
    List<ClassSession> inWindowForBatches(@Param("academyId") UUID academyId, @Param("from") Instant from,
                                          @Param("to") Instant to, @Param("batchIds") Collection<UUID> batchIds);
}
