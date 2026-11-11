package com.physicscube.luma.zoom.web;

import com.physicscube.luma.zoom.ZoomWebhookIngress;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public ingress for Zoom. Zoom is not an academy user, so there is no JWT: the
 * signature over the raw body is the credential. Bodies are read with a hard size
 * limit before anything else happens.
 */
@RestController
class ZoomWebhookController {
    private final ZoomWebhookIngress ingress;

    ZoomWebhookController(ZoomWebhookIngress ingress) {
        this.ingress = ingress;
    }

    @PostMapping("/api/v1/zoom/webhooks")
    ResponseEntity<?> receive(HttpServletRequest request) throws IOException {
        if (!ingress.enabled()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            return ResponseEntity.status(415).build();
        }
        int limit = ingress.maxBodyBytes();
        if (request.getContentLengthLong() > limit) {
            return ResponseEntity.status(413).build();
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            return ResponseEntity.status(413).build();
        }
        ZoomWebhookIngress.Outcome outcome = ingress.receive(request.getHeader("x-zm-request-timestamp"),
                request.getHeader("x-zm-signature"), body);
        return outcome.body() == null ? ResponseEntity.status(outcome.status()).build()
                : ResponseEntity.status(outcome.status()).body((Map<String, String>) outcome.body());
    }
}
