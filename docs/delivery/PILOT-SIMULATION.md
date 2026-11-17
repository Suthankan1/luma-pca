# Simulated pilot — Phase 2 dress rehearsal (17 Nov 2026)

Phase 2 of [PILOT-PLAN.md](PILOT-PLAN.md) runs one batch for two weeks and compares LUMA with the teacher's register for
every class. Before the academy does that with real students, the development team ran the same comparison with test
data: `PilotSimulationIntegrationTest` (backend, CI on every backend change).

## What it does

- One course, batches A (30 students) and B (2 students); the six classes (Mon/Wed/Fri, 2–13 Mar 2026, 2 hours) serve
  both batches. One student is in both batches; one joins batch A in week 2.
- The owner records the rule (75 % connected, late after 10 minutes). Each class is scheduled with the webinar ID; Zoom's
  start event links the run to the class; Zoom's participant report arrives after the end; staff finalise.
- Each student does one of ten things per class, rotating: whole class, early join, 10 minutes late (still on time),
  15 minutes late, 30 minutes only, absent, two overlapping devices plus a rejoin, exactly 75 %, one minute short of 75 %,
  late and short. One student always joins from another Zoom account but registered with the academy email; another
  always joins from a family member's account. The host and a guest are in every class.
- The **register** is written by hand from those visits, not computed with LUMA's rule.

## Result

| Check | Result |
| --- | --- |
| Register entries vs final records | **189 vs 189, 0 differences** (95 present, 20 late, 74 absent) |
| Student in two batches of a class | One record per class |
| Student who joined in week 2 | No record before joining; recorded after |
| Joined from another account, registered with the academy email | Matched every class (registration email) |
| Family member's account | Waiting in Zoom review after class 1; one staff decision matched it for all six classes |
| Host | Never a student, never waiting for review |
| Guests | Waiting for review (one per class), never counted |
| Batch CSV for the fortnight | 177 rows (batch A), every outcome equal to the register |

## Found and fixed by this rehearsal

Zoom review listed, and accepted decisions for, only Zoom emails seen in **live** webhook events. People who appeared
only in Zoom's participant report (webhooks missed, or a run found with "Check a webinar") could not be reviewed or
matched, so a student joining from another account would have stayed absent. Zoom review now uses both live events and
reports, and leaves out anyone already matched by registration email (`ZoomParticipantService.unmatched`/`decide`).

## What the real Phase 2 adds

Real students, a real teacher's register, real Zoom behaviour over two weeks (KNOWN-LIMITS: several occurrences of one
recurring webinar), and staff using the screens daily. Exit criteria are unchanged (PILOT-PLAN.md).
