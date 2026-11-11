package com.physicscube.luma.identity.domain;

/**
 * Academy-level role grants. A role alone never implies access to every course or
 * batch: scoped staff assignments and record ownership are checked per operation.
 */
public enum Role {
    ACADEMY_OWNER,
    ACADEMY_ADMIN,
    INSTRUCTOR,
    ATTENDANCE_OFFICER,
    ANALYST,
    STUDENT
}
