# Production go-live checklist — release gate

**None of these are pre-approved.** Academy owner and engineering lead must sign off before connecting real student records.

- [ ] Academy approves courses/batches identity policy, attendance thresholds, manual review, data retention, reporting and leaderboard consent.
- [ ] Zoom license/scopes and actual join/leave/rejoin/register/report proof of concept documented.
- [ ] Account provisioning, MFA for privileged staff, secure signing keys, key rotation, password reset, token reuse/revocation and CSRF verified.
- [ ] Student self-only and cross-academy/course/batch IDOR regression tests pass.
- [ ] Webhook signatures verified, replay protected and durable receipts/worker idempotency validated in fault tests.
- [ ] Attendance interval math, recurring occurrences, rejoin, pagination and post-webinar reports match accepted baseline data.
- [ ] No Zoom-native emoji attribution assumed without demonstrated proof; clear portal consent and anti-spam policy.
- [ ] Load/capacity and account rate limits checked for real student volumes.
- [ ] TLS/secure secrets, least-privilege AWS IAM, private RDS/S3, encrypted backups, alerts and audit logs deployed.
- [ ] Backup **restoration drill** succeeds; incident response, rollback and on-call contact documented.
- [ ] Production privacy/legal review completed (including minors, retention and deletion policies).
- [ ] Academy UAT and support/handover materials signed off. Enable gradual pilot and monitor exception rate.
