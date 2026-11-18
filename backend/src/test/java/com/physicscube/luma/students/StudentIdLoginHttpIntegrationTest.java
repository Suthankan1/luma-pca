package com.physicscube.luma.students;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The Academy-requested sign-in (student ID as username and password), switched on, over real HTTP. */
@TestPropertySource(properties = {
    "luma.student-id-login.enabled=true",
    "luma.student-id-login.academy-id=6f2a9d3e-0c1b-4e57-9a8d-3b2c1d0e9f71",
    "luma.rate-limit.enabled=true",
    "luma.rate-limit.sign-in.limit=6"})
class StudentIdLoginHttpIntegrationTest extends StaffApiIntegrationTest {
    static final UUID ACADEMY = UUID.fromString("6f2a9d3e-0c1b-4e57-9a8d-3b2c1d0e9f71");
    static final String LOGIN = "/api/v1/auth/student-id-login";
    private static final AtomicInteger CLIENT = new AtomicInteger(10);

    String admin;
    AcademyFixtures fixtures;
    String batch;
    String client;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM rate_limit_counters");
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Physics Cube Test')", ACADEMY);
        admin = tokenFor(ACADEMY, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "MON");
        client = "203.0.113." + CLIENT.incrementAndGet();
    }

    private HttpResponse<String> signIn(String studentId, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("studentId", studentId);
        body.put("password", password);
        return api.postFrom(client, LOGIN, HttpApi.json(body));
    }

    private String student(String number) {
        String id = (String) fixtures.student(number, number.toLowerCase() + "@example.invalid").get("id");
        assertEquals(201, fixtures.enroll(batch, id, "2027-01-10").statusCode());
        return id;
    }

    @Test
    void aStudentSignsInWithTheirIdAndSeesOnlyTheirOwnData() {
        String own = student("PCA001");
        String other = student("PCA002");

        HttpResponse<String> response = signIn(" pca001 ", "PCA001");
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> session = HttpApi.parse(response);
        String token = (String) session.get("accessToken");
        assertNotNull(session.get("refreshToken"));
        assertFalse(response.body().contains("mfaRequired"), "no second factor in the requested flow");

        Map<String, Object> me = HttpApi.parse(api.get("/api/v1/me/student", token));
        assertEquals(own, me.get("id"));
        assertEquals("PCA001", me.get("studentNumber"));
        assertEquals(200, api.get("/api/v1/me/enrollments", token).statusCode());
        assertEquals(200, api.get("/api/v1/me/attendance", token).statusCode());

        // Another student's records and every staff route stay closed.
        assertEquals(403, api.get("/api/v1/students/" + other, token).statusCode());
        assertEquals(403, api.get("/api/v1/batches/" + batch + "/enrollments", token).statusCode());
        assertEquals(403, api.get("/api/v1/students", token).statusCode());
        assertEquals(403, api.get("/api/v1/academy/classes", token).statusCode());

        // Logout ends the session.
        assertEquals(204, api.post("/api/v1/auth/logout", HttpApi.json(Map.of("refreshToken", session.get("refreshToken")))).statusCode());
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", session.get("refreshToken")))).statusCode());

        // The account was activated with a password nobody knows: email sign-in stays closed.
        Map<String, String> emailLogin = new LinkedHashMap<>();
        emailLogin.put("email", "pca001@example.invalid");
        emailLogin.put("password", (String) me.get("studentNumber"));
        assertEquals(401, api.postFrom(client, "/api/v1/auth/login", HttpApi.json(emailLogin)).statusCode());
    }

    @Test
    void everyRefusalLooksTheSame() {
        String student = student("PCA001");
        HttpResponse<String> wrong = signIn("PCA001", "PCA002");
        assertEquals(401, wrong.statusCode());
        HttpResponse<String> unknown = signIn("PCA999", "PCA999");
        assertEquals(401, unknown.statusCode());
        assertEquals(HttpApi.parse(unknown).get("detail"), HttpApi.parse(wrong).get("detail"));
        assertEquals(400, signIn("", "").statusCode());
        assertEquals(401, signIn("PCA 001", "PCA 001").statusCode(), "not a valid ID");

        // A deactivated student cannot sign in.
        Map<String, Object> view = HttpApi.parse(api.get("/api/v1/students/" + student, admin));
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("fullName", view.get("fullName"));
        update.put("active", false);
        update.put("version", view.get("version"));
        assertEquals(200, api.put("/api/v1/students/" + student, HttpApi.json(update), admin).statusCode());
        assertEquals(401, signIn("PCA001", "PCA001").statusCode());
    }

    @Test
    void anAccountWithAnyStaffRoleCanNeverSignInThisWay() {
        String student = student("PCA001");
        UUID account = jdbc.queryForObject("SELECT account_id FROM students WHERE id = ?::uuid", UUID.class, student);
        jdbc.update("INSERT INTO account_roles (account_id, role) VALUES (?, 'ACADEMY_ADMIN')", account);
        assertEquals(401, signIn("PCA001", "PCA001").statusCode());
    }

    @Test
    void studentsOfAnotherAcademyCannotSignIn() {
        UUID otherAcademy = academy("Other academy");
        String otherAdmin = tokenFor(otherAcademy, Role.ACADEMY_ADMIN);
        new AcademyFixtures(api, otherAdmin).student("PCA500", "pca500@example.invalid");
        assertEquals(401, signIn("PCA500", "PCA500").statusCode());
    }

    @Test
    void guessingIsRateLimitedPerClient() {
        student("PCA001");
        for (int i = 0; i < 6; i++) {
            assertEquals(401, signIn("PCA00" + (i + 2), "x").statusCode());
        }
        HttpResponse<String> limited = signIn("PCA001", "PCA001");
        assertEquals(429, limited.statusCode());
        assertNotNull(limited.headers().firstValue("Retry-After").orElse(null));
    }

    @Test
    void theSignInPageCanAskWhetherTheRouteIsOn() {
        assertEquals(Map.of("enabled", true), HttpApi.parse(api.get(LOGIN, null)));
    }
}
