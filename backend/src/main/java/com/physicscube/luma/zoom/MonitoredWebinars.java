package com.physicscube.luma.zoom;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which webinar IDs the academy monitors, for the webhook ingress (a monitored webinar's events are stored even when a
 * deployment allow-list is set). Cached briefly; any change to the monitors clears it.
 */
@Component
class MonitoredWebinars {
    private static final long TTL_MS = 30_000;

    private record Entry(Set<String> ids, long expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    MonitoredWebinars(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean isMonitored(UUID academyId, String webinarId) {
        if (academyId == null || webinarId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Entry entry = cache.get(academyId);
        if (entry == null || now >= entry.expiresAt()) {
            entry = new Entry(Set.copyOf(jdbc.queryForList(
                    "SELECT webinar_id FROM zoom_webinar_monitors WHERE academy_id = ? AND active", String.class, academyId)), now + TTL_MS);
            cache.put(academyId, entry);
        }
        return entry.ids().contains(webinarId);
    }

    void changed(UUID academyId) {
        cache.remove(academyId);
    }
}
