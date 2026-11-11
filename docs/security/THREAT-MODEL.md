# Initial threat model — update before pilot

| Threat | Impact | Mitigation / test |
| --- | --- | --- |
| Student accesses peer's record via IDOR | High | Authenticated resource ownership tests; batch/course scoped queries |
| Teacher exports unauthorized batch | High | Server-side grants + tests across multiple batches |
| Stolen refresh cookie/token | High | Secure HttpOnly SameSite cookies, refresh rotation, reuse detection, session revoke, CSRF |
| Compromised JWT signing key | Critical | Private secret/KMS, JWKS rotation, restricted permissions, emergency revocation |
| Forged/replayed Zoom webhooks | High | Signature + timestamp validation, idempotent receipt, trusted webhook secret, replay window |
| Duplicate/out-of-order or missing Zoom events | High | Durable inbox, unique receipts, merged intervals, report reconciliation, operator queue |
| Attendee display name spoofing | High | Stable authorized registration ID mapping; manual review |
| API credential leakage | High | Only backend OAuth, Secrets Manager, least-privilege scopes, no logs |
| React/Next XSS or CSRF | High | CSP, React escapes, CSRF on BFF, no localStorage tokens, cookie restrictions |
| Reaction spamming / leaderboard gaming | Medium | Enrollment check, signed-in portal, rate limits, dedup, published rules |
| PII in reports, backups or logs | High | Encryption, access reviews, bounded retention, private S3 URLs, redaction |
| Availability / queue backlog | Medium | SQS DLQ, alerts, replay ops, tested restore and capacity checks |

Use STRIDE plus data-flow diagrams for detailed review before production. A security review is required on each module’s first externally accessible endpoint.
