package com.physicscube.luma.identity.domain;

/**
 * Academy-level role grants (owner decision 2026-11-20: two roles). STAFF may do every
 * staff action within its own academy; STUDENT reaches only its own records. The academy
 * always comes from the verified token, and students are checked for record ownership.
 */
public enum Role {
    STAFF,
    STUDENT
}
