package com.physicscube.luma.students;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** By default the Academy-requested sign-in is off: the route does nothing. */
class StudentIdLoginDisabledHttpIntegrationTest extends StaffApiIntegrationTest {
    @Test
    void theRouteIsOffByDefault() {
        assertEquals(Map.of("enabled", false), HttpApi.parse(api.get("/api/v1/auth/student-id-login", null)));
        assertEquals(404, api.post("/api/v1/auth/student-id-login",
                HttpApi.json(Map.of("studentId", "PCA001", "password", "PCA001"))).statusCode());
    }
}
