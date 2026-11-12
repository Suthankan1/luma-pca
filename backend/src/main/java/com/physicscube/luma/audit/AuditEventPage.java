package com.physicscube.luma.audit;

import java.util.List;

/** @param nextBefore pass as {@code before} for older events; {@code null} on the last page */
public record AuditEventPage(List<AuditEventView> items, String nextBefore) {
}
