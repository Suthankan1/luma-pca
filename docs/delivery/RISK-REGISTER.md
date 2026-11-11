# Risk register

| Risk | Severity | Mitigation | Owner |
| --- | --- | --- | --- |
| Zoom license/report access insufficient | Critical | Admin proof of concept before delivery promise | Integration lead + academy admin |
| Participant identity mismatch | High | Registration IDs, stable roster mapping, manual review | Product + backend |
| JWT/BFF CSRF or session flaw | Critical | Security review, refresh rotation, targeted e2e tests | Security lead |
| Webhook loss/dup/out-of-order | Critical | Signed receipt, inbox/outbox, SQS/DLQ, reconciliation | Backend lead |
| Cross-batch information leakage | Critical | Scope-based query filters and IDOR test matrix | Backend lead |
| Sensitive minor data/privacy | Critical | Privacy review, retention, opt-in rankings and access logs | Academy owner |
| Over-optimistic go-live date | High | Milestone acceptance gates, pilot, owner sign-off | Project lead |
| AWS recurring cost | Medium | Forecast by realistic concurrency and quote alternative hosts | Operations lead |
| Native emoji reaction attribution not available | High | Portal-based reactions; no native leaderboard claim | Product lead |
| Team turnover/AI context drift | Medium | ADRs, checkpoints, small commits and handover docs | Project lead |
