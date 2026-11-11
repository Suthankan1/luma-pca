package com.physicscube.luma.batches;

import java.util.Set;
import java.util.UUID;

/** Which batches of the academy a caller may read: all of them, or exactly {@code batchIds}. */
public record BatchScope(boolean all, Set<UUID> batchIds) {
    public boolean includes(UUID batchId) {
        return all || batchIds.contains(batchId);
    }
}
