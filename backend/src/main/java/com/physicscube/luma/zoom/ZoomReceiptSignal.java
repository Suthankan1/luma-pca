package com.physicscube.luma.zoom;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Raised when a webhook is stored, so the worker processes it at once instead of on its idle schedule. A hint only. */
@Component
public class ZoomReceiptSignal {
    private final AtomicBoolean pending = new AtomicBoolean();

    public void raise() {
        pending.set(true);
    }

    /** Whether new work was signalled since the last call; clears the signal. */
    public boolean take() {
        return pending.getAndSet(false);
    }
}
