package com.physicscube.luma.zoom.web;

import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.zoom.ZoomImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The academy's upcoming Zoom webinars, and turning their runs into classes. */
@RestController
class ZoomImportController {
    private final ZoomImportService imports;

    ZoomImportController(ZoomImportService imports) {
        this.imports = imports;
    }

    record ImportRequest(@NotBlank @Size(max = 30) String webinarId, @NotEmpty @Size(max = 20) List<UUID> batchIds,
                         @Size(max = 300) List<@Size(max = 100) String> occurrenceIds, @Size(max = 200) String title) {
    }

    @GetMapping("/api/v1/zoom/upcoming-webinars")
    List<ZoomImportService.UpcomingWebinar> upcoming(JwtAuthenticationToken authentication) {
        return imports.upcoming(CurrentAccount.from(authentication));
    }

    @PostMapping("/api/v1/zoom/webinar-imports")
    @ResponseStatus(HttpStatus.CREATED)
    ZoomImportService.ImportResult importRuns(JwtAuthenticationToken authentication, @Valid @RequestBody ImportRequest request) {
        return imports.importRuns(CurrentAccount.from(authentication), request.webinarId(), request.batchIds(), request.occurrenceIds(),
                request.title());
    }
}
