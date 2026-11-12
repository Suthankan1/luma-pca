package com.physicscube.luma.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audited action as shown to owners and administrators.
 *
 * @param actorEmail the account that acted, if it still exists; {@code null} for system actions or a removed account
 * @param targetLabel a readable name for the target where one exists (an account's email, a batch code, a class title)
 * @param details the recorded details; by construction never passwords, tokens or student personal data
 */
public record AuditEventView(UUID id, Instant occurredAt, String action, UUID actorAccountId, String actorEmail,
                             String targetType, UUID targetId, String targetLabel, Map<String, Object> details) {
}
