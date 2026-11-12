package com.physicscube.luma.enrollments.web;

import com.physicscube.luma.enrollments.StudentInvitationService;
import com.physicscube.luma.identity.InvitationEmailQueue;
import com.physicscube.luma.identity.InvitationEmailQueue.Progress;
import com.physicscube.luma.identity.InvitationEmailQueue.QueueResult;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Bulk invitation emails for students; sending happens in the background. */
@RestController
class StudentInvitationController {
    private final StudentInvitationService invitations;
    private final InvitationEmailQueue queue;

    StudentInvitationController(StudentInvitationService invitations, InvitationEmailQueue queue) {
        this.invitations = invitations;
        this.queue = queue;
    }

    /** @param batchId null for every active student of the academy */
    record BulkInviteRequest(UUID batchId, Boolean resend, Boolean dryRun) {
    }

    @PostMapping("/api/v1/student-invitations")
    QueueResult invite(JwtAuthenticationToken authentication, @RequestBody BulkInviteRequest request) {
        return invitations.invite(CurrentAccount.from(authentication), request.batchId(), Boolean.TRUE.equals(request.resend()),
                Boolean.TRUE.equals(request.dryRun()));
    }

    @GetMapping("/api/v1/student-invitations/failures")
    java.util.List<StudentInvitationService.FailedInvitation> failures(JwtAuthenticationToken authentication) {
        return invitations.failures(CurrentAccount.from(authentication));
    }

    @GetMapping("/api/v1/student-invitations/progress")
    Progress progress(JwtAuthenticationToken authentication) {
        return queue.progress(CurrentAccount.from(authentication));
    }
}
