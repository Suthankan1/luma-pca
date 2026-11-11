package com.physicscube.luma.students;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface StudentRepository extends JpaRepository<Student, UUID> {
    Optional<Student> findByIdAndAcademyId(UUID id, UUID academyId);

    Optional<Student> findByAccountIdAndAcademyId(UUID accountId, UUID academyId);

    boolean existsByAcademyIdAndStudentNumber(UUID academyId, String studentNumber);

    List<Student> findByAcademyIdAndIdIn(UUID academyId, Collection<UUID> ids);

    /** Keyset page ordered by student number; {@code after} is "" for the first page. */
    @Query("""
            select s from Student s
            where s.academyId = :academyId and s.studentNumber > :after
              and (:active is null or s.active = :active)
            order by s.studentNumber
            """)
    List<Student> page(@Param("academyId") UUID academyId, @Param("after") String after,
                       @Param("active") Boolean active, Limit limit);
}
