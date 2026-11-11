package com.physicscube.luma.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates courses, batches and students through the real API, as an administrator would. */
public final class AcademyFixtures {
    private final HttpApi api;
    private final String admin;

    public AcademyFixtures(HttpApi api, String adminToken) {
        this.api = api;
        this.admin = adminToken;
    }

    public String course(String code) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", code);
        fields.put("title", "Course " + code);
        return id(api.post("/api/v1/courses", HttpApi.json(fields), admin));
    }

    public String batch(String courseId, String code) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("courseId", courseId);
        fields.put("code", code);
        fields.put("name", "Batch " + code);
        fields.put("startsOn", "2027-01-10");
        return id(api.post("/api/v1/batches", HttpApi.json(fields), admin));
    }

    public HttpResponse<String> postStudent(String number, String name, String email) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("studentNumber", number);
        fields.put("fullName", name);
        fields.put("email", email);
        return api.post("/api/v1/students", HttpApi.json(fields), admin);
    }

    /** @return the created student as JSON */
    public Map<String, Object> student(String number, String email) {
        HttpResponse<String> response = postStudent(number, "Student " + number, email);
        assertEquals(201, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    public HttpResponse<String> enroll(String batchId, String studentId, String enrolledOn) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("studentId", studentId);
        fields.put("enrolledOn", enrolledOn);
        return api.post("/api/v1/batches/" + batchId + "/enrollments", HttpApi.json(fields), admin);
    }

    private static String id(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }
}
