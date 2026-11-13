package com.physicscube.luma.zoom;

import java.util.UUID;
import org.springframework.context.ApplicationContext;

/** Lets tests outside the Zoom package store a receipt as the webhook ingress would. */
public final class ZoomTestReceipts {
    private ZoomTestReceipts() {
    }

    public static void store(ApplicationContext context, UUID academyId, String eventType, long eventTs, byte[] body) {
        context.getBean(ZoomWebhookReceipts.class).store(academyId, eventType, eventTs, body);
    }
}
