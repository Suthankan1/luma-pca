# Handover — LUMA for Physics Cube Academy (17 Nov 2026)

## What is delivered

A working web application, tested end to end, that replaces roll call in the academy's Zoom webinars:

- **Staff portal** (owner, administrator, instructor, attendance officer, analyst; every staff sign-in uses an
  authenticator app): courses, batches, students (one by one or from CSV), enrolments, staff and their batches, classes and
  weekly series (or **imported from the academy's upcoming Zoom webinars** in one step), invitations by email, and a home page
  with today's classes and everything that needs attention.
- **Zoom:** signed webhooks from the academy's Zoom account, automatic linking of each webinar run to its class, people
  matched to students by email or registration email, staff decisions for the rest, Zoom's participant report after
  every class, and "Check a webinar" by join link or ID for any past run.
- **Attendance:** live provisional view; final present / late / absent from Zoom's report by the academy's own rule;
  every ready class finalised at once, or **automatically** once Zoom's report is in (owner's choice); corrections with
  reasons, recalculation, student appeals, email notices; per-student rates per batch, totals per batch,
  CSV exports.
- **Student portal** (phone first): own classes, final attendance, appeals, password change; reactions and questions
  during class if the academy switches engagement on (consent-based, staff-only rankings).
- **Privacy and operations:** activity log, owner-only erasure on request, nightly retention, security headers, rate
  limits, backups with a tested restore drill, Terraform for AWS (not applied), runbooks.

Guides: [STAFF-GUIDE.md](STAFF-GUIDE.md), [STUDENT-GUIDE.md](STUDENT-GUIDE.md). Limits: [KNOWN-LIMITS.md](KNOWN-LIMITS.md).

## Evidence

| What | Result |
| --- | --- |
| Backend tests (PostgreSQL 17, every endpoint with out-of-scope and other-academy cases) | All passing in CI on every change |
| Browser tests (desktop and phone, accessibility WCAG 2.1 A/AA, security headers) | All passing in CI |
| Real-stack run (real API, database, web server, SMTP, Chrome) | Passing in CI on every pull request |
| User acceptance script, developer run | 17 of 17 passed ([UAT-RESULTS.md](UAT-RESULTS.md)) |
| Pilot rehearsal, two weeks of one batch vs a hand-written register | 189 of 189 records match ([PILOT-SIMULATION.md](PILOT-SIMULATION.md)) |
| Real Zoom account | Webhooks, report, registrants, past runs and matching proven ([ZOOM-TEST-WEBINAR.md](../integration/ZOOM-TEST-WEBINAR.md)) |
| Restore drill | Passed 17 Nov (25 migrations, 37 tables) |

Every slice and its pull request: [PROGRESS.md](../ai/checkpoints/PROGRESS.md).

## What the academy does next

1. Fill in [SIGN-OFF.md](SIGN-OFF.md) (rule, retention, registration policy, hosting owner and budget, domain, mail sender).
2. Choose hosting; deploy with [runbooks/DEPLOY.md](runbooks/DEPLOY.md) (or the Terraform in `infra/terraform`), then
   create the first owner with `scripts/bootstrap-owner.sh` ([runbooks/FIRST-OWNER.md](runbooks/FIRST-OWNER.md)).
3. The Zoom app already has every read scope LUMA uses, including `webinar:read:list_webinars:admin` for **Import classes
   from Zoom** (added 17 Nov). Point the Zoom app's event subscription at `https://<domain>/api/v1/zoom/webhooks`; keep "registration required" with
   Zoom sign-in on class webinars.
4. Phase 1: staff repeat [UAT-SCRIPT.md](UAT-SCRIPT.md) on staging and the load test at 600 users.
5. Phase 2 and 3 of [PILOT-PLAN.md](PILOT-PLAN.md): one batch for two weeks against the register, then all batches.

## Running it locally

See the README's quick start. `scripts/staff-ui-real-stack.sh` starts the whole stack with test data and runs the
end-to-end browser tests against it.
