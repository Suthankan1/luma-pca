package com.physicscube.luma.audit.web;

import com.physicscube.luma.audit.AuditEventPage;
import com.physicscube.luma.audit.AuditQueryService;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The academy's activity log: read-only, owners and administrators. */
@RestController
class AuditEventController {
    private final AuditQueryService audit;

    AuditEventController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/v1/audit-events")
    AuditEventPage list(JwtAuthenticationToken authentication, @RequestParam(required = false) String category,
                        @RequestParam(required = false) UUID targetId, @RequestParam(required = false) String before,
                        @RequestParam(defaultValue = "50") int limit) {
        return audit.list(CurrentAccount.from(authentication), category, targetId, before, limit);
    }
}
