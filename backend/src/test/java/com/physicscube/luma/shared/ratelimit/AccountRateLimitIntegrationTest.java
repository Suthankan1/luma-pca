package com.physicscube.luma.shared.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Heavier signed-in actions are limited per account (429 with Retry-After); other accounts are unaffected. */
@TestPropertySource(properties = {"luma.rate-limit.enabled=true", "luma.rate-limit.sign-in.limit=1000",
        "luma.rate-limit.heavy-staff.limit=2"})
class AccountRateLimitIntegrationTest extends StaffApiIntegrationTest {
    @Test
    void exportsAreLimitedPerAccount() {
        resetDatabase();
        UUID academyId = academy("Physics Cube Test");
        String admin = tokenFor(academyId, Role.STAFF);
        String other = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        String path = "/api/v1/batches/" + batch + "/attendance/export?from=2026-02-01&to=2026-02-28";

        assertEquals(200, api.get(path, admin).statusCode());
        assertEquals(200, api.get(path, admin).statusCode());
        HttpResponse<String> refused = api.get(path, admin);
        assertEquals(429, refused.statusCode());
        assertTrue(Long.parseLong(refused.headers().firstValue("Retry-After").orElse("0")) > 0);
        assertEquals(200, api.get(path, other).statusCode(), "another account is not affected");
        assertEquals(200, api.get("/api/v1/batches/" + batch, admin).statusCode(), "ordinary reads are not limited");
    }
}
