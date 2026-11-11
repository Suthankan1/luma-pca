# Requirements-to-diagrams index

| Requirement | Diagrams | Security / tests |
| --- | --- | --- |
| Student/staff JWT | auth-flow.mmd | JWT-AUTH.md, THREAT-MODEL.md |
| Multiple courses and batches | domain-erd.mmd | SCHEMA-PLAN.md, ROLE matrix, enrollment tests |
| Zoom signed webhook | zoom-events.mmd | ZOOM-RULES.md, duplicate/fault tests |
| Attendance with reconnect | domain-erd.mmd, zoom-events.mmd | Interval tests, report reconciliation tests |
| Deployable runtime | deployment.mmd | GO-LIVE-CHECKLIST.md, RESTORE.md |
| Academy+student UI | context.mmd | DESIGN-SYSTEM.md, E2E |
