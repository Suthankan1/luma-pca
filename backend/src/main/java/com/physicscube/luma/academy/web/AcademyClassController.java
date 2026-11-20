package com.physicscube.luma.academy.web;

import com.physicscube.luma.academy.AcademyClassService;
import com.physicscube.luma.academy.AcademyClassService.AcademyClassView;
import com.physicscube.luma.academy.AcademyClassService.SyncResult;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Academy's own classes (read-only source) and their LUMA batches. Staff only. */
@RestController
@RequestMapping("/api/v1/academy/classes")
class AcademyClassController {
    private final AcademyClassService classes;

    AcademyClassController(AcademyClassService classes) {
        this.classes = classes;
    }

    record LinkRequest(@NotBlank @Size(max = 200) String className, @NotNull UUID batchId) {
    }

    record SyncRequest(@NotBlank @Size(max = 200) String className) {
    }

    @GetMapping
    List<AcademyClassView> list(JwtAuthenticationToken authentication) {
        return classes.classes(CurrentAccount.from(authentication));
    }

    @PutMapping("/link")
    AcademyClassView link(JwtAuthenticationToken authentication, @Valid @RequestBody LinkRequest request) {
        return classes.link(CurrentAccount.from(authentication), request.className(), request.batchId());
    }

    @PostMapping("/sync")
    SyncResult sync(JwtAuthenticationToken authentication, @Valid @RequestBody SyncRequest request) {
        return classes.sync(CurrentAccount.from(authentication), request.className());
    }
}
