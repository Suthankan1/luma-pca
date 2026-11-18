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

    Optional<Student> findByAcademyIdAndStudentNumber(UUID academyId, String studentNumber);

    Optional<Student> findByAccountIdAndAcademyId(UUID accountId, UUID academyId);

    boolean existsByAcademyIdAndStudentNumber(UUID academyId, String studentNumber);

    @Query("select s.studentNumber from Student s where s.academyId = :academyId and s.studentNumber in :numbers")
    List<String> existingNumbers(@Param("academyId") UUID academyId, @Param("numbers") Collection<String> numbers);

    List<Student> findByAcademyIdAndIdIn(UUID academyId, Collection<UUID> ids);

    List<Student> findByAcademyIdAndAccountIdIn(UUID academyId, Collection<UUID> accountIds);

    @Query("select s.accountId from Student s where s.academyId = :academyId and s.active = true")
    List<UUID> activeAccountIds(@Param("academyId") UUID academyId);

    @Query("select s.accountId from Student s where s.academyId = :academyId and s.active = true and s.id in :ids")
    List<UUID> activeAccountIds(@Param("academyId") UUID academyId, @Param("ids") Collection<UUID> ids);

    /** Keyset page ordered by student number; {@code after} is "" for the first page. */
    @Query("""
            select s from Student s
            where s.academyId = :academyId and s.studentNumber > :after
              and (:active is null or s.active = :active)
            order by s.studentNumber
            """)
    List<Student> page(@Param("academyId") UUID academyId, @Param("after") String after,
                       @Param("active") Boolean active, Limit limit);

    /**
     * As {@link #page}, keeping students whose number or name contains {@code pattern} (a
     * lower-case LIKE pattern with \\ escaping) or whose sign-in is {@code accountId}.
     */
    @Query("""
            select s from Student s
            where s.academyId = :academyId and s.studentNumber > :after
              and (:active is null or s.active = :active)
              and (lower(s.studentNumber) like :pattern escape '\\' or lower(s.fullName) like :pattern escape '\\'
                   or s.accountId = :accountId)
            order by s.studentNumber
            """)
    List<Student> search(@Param("academyId") UUID academyId, @Param("after") String after, @Param("active") Boolean active,
                         @Param("pattern") String pattern, @Param("accountId") UUID accountId, Limit limit);
}
