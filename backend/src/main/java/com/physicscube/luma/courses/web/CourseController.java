package com.physicscube.luma.courses.web;

import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.courses.CourseView;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Courses are deactivated, never deleted, so attendance evidence keeps its context. */
@RestController
@RequestMapping("/api/v1/courses")
class CourseController {
    private final CourseService courses;

    CourseController(CourseService courses) {
        this.courses = courses;
    }

    record CreateCourseRequest(@NotNull @Pattern(regexp = Codes.PATTERN) String code,
                               @NotBlank @Size(max = 200) String title) {
    }

    record UpdateCourseRequest(@NotNull @Pattern(regexp = Codes.PATTERN) String code,
                               @NotBlank @Size(max = 200) String title,
                               @NotNull Boolean active,
                               @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<CourseView> list(JwtAuthenticationToken authentication, @RequestParam(required = false) Boolean active) {
        return courses.list(CurrentAccount.from(authentication), active);
    }

    @GetMapping("/{courseId}")
    CourseView get(JwtAuthenticationToken authentication, @PathVariable UUID courseId) {
        return courses.get(CurrentAccount.from(authentication), courseId);
    }

    @PostMapping
    ResponseEntity<CourseView> create(JwtAuthenticationToken authentication, @Valid @RequestBody CreateCourseRequest request) {
        CourseView created = courses.create(CurrentAccount.from(authentication), request.code(), request.title());
        return ResponseEntity.created(URI.create("/api/v1/courses/" + created.id())).body(created);
    }

    @PutMapping("/{courseId}")
    CourseView update(JwtAuthenticationToken authentication, @PathVariable UUID courseId,
                      @Valid @RequestBody UpdateCourseRequest request) {
        return courses.update(CurrentAccount.from(authentication), courseId, request.code(), request.title(),
                request.active(), request.version());
    }
}
