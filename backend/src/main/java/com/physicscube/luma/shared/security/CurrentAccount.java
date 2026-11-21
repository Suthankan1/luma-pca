package com.physicscube.luma.shared.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated caller as services see it. The academy comes from the verified
 * token and the roles from the database at authentication time; neither is ever
 * taken from a request path, query or body.
 */
public record CurrentAccount(UUID accountId, UUID academyId, Set<String> roles) {
    public static final String STAFF = "STAFF";
    public static final String STUDENT = "STUDENT";

    public static CurrentAccount from(JwtAuthenticationToken authentication) {
        return new CurrentAccount(UUID.fromString(authentication.getName()),
                UUID.fromString(authentication.getToken().getClaimAsString("academy_id")),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .filter(authority -> authority.startsWith("ROLE_"))
                        .map(authority -> authority.substring(5)).collect(Collectors.toUnmodifiableSet()));
    }

    /** Staff of the caller's own academy: every staff action, across all its courses and batches. */
    public boolean isStaff() {
        return roles.contains(STAFF);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public void requireRole(String role) {
        if (!hasRole(role)) {
            throw new AccessDeniedException(role + " role required");
        }
    }

    /** Staff of the caller's own academy. */
    public void requireStaff() {
        if (!isStaff()) {
            throw new AccessDeniedException("Staff role required");
        }
    }
}
