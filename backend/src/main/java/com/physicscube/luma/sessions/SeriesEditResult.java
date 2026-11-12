package com.physicscube.luma.sessions;

import java.util.List;

/**
 * Changes to the rest of a weekly series: the classes as they would be (or now are) and any
 * that would overlap a scheduled class of the same batches. Nothing changes while there are
 * conflicts.
 *
 * @param applied true only when the classes were changed; false for a preview or when there are conflicts
 */
public record SeriesEditResult(boolean applied, List<SeriesResult.Occurrence> occurrences, List<SeriesResult.Conflict> conflicts) {
}
