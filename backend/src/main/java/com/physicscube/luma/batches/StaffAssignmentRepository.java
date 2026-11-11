package com.physicscube.luma.batches;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, UUID> {
    Optional<StaffAssignment> findByIdAndAcademyId(UUID id, UUID academyId);

    List<StaffAssignment> findByAcademyIdAndBatchIdOrderByRoleAscCreatedAtAsc(UUID academyId, UUID batchId);

    List<StaffAssignment> findByAcademyIdAndAccountIdAndRoleIn(UUID academyId, UUID accountId, Collection<String> roles);

    boolean existsByBatchIdAndAccountIdAndRoleIn(UUID batchId, UUID accountId, Collection<String> roles);

    boolean existsByBatchIdAndAccountIdAndRole(UUID batchId, UUID accountId, String role);

    @Query("""
            select distinct a.batchId from StaffAssignment a
            where a.academyId = :academyId and a.accountId = :accountId and a.role in :roles
            """)
    List<UUID> batchIds(@Param("academyId") UUID academyId, @Param("accountId") UUID accountId,
                        @Param("roles") Collection<String> roles);
}
