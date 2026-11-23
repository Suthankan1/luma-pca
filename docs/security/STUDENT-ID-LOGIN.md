# Student ID sign-in — Academy-requested, weak by design

**Status:** implemented (ACAD-2), **off by default**. Switch on only after the Academy accepts the risk in writing.

## What the Academy asked for

Students type **only their registration ID** (for example `PCA12345`) and see their attendance straight away. No
password, registration, invitation, password creation or second factor. (Owner decision 2026-11-21; the earlier
version asked for the ID twice, as username and password, which added no security.)

With the flag enabled, the landing page’s Student sign-in link opens a form with one **Registration ID** field, and a
successful sign-in lands on the student’s attendance report (`/student`). The shared `/sign-in` page also provides the
academy account form for staff and existing email accounts. Staff sign-in is unchanged (email and password).

The student never sees it, but a normal LUMA session is still opened behind the ID: it is what keeps each student to
their own records, keeps every staff route closed to them, and lets them sign out.

## Why this is not secure authentication

A student ID is an identifier, not a secret: it is printed on receipts and class lists, shared on WhatsApp, and often
sequential. Anyone who knows or guesses an ID can sign in as that student and see that student's classes, attendance
and Zoom links, and act as them (reactions, attendance appeals). LUMA must not describe this as secure, and it does not
protect staff accounts in any way.

## What LUMA does to limit the damage

- **Off unless switched on in the API:** set `LUMA_STUDENT_ID_LOGIN_ENABLED=true` and configure
  `LUMA_STUDENT_ID_LOGIN_ACADEMY_ID` (defaults to the Zoom webhook academy). The web app reads availability from
  `GET /api/v1/auth/student-id-login` on the sign-in page. While off, the API denies ID sign-in with 404 and the
  page offers email sign-in. Disabling the flag blocks new ID sign-ins. Existing sessions follow normal session
  revocation and expiry rules.
- **Students only:** the session is opened only for an account whose **sole** role is `STUDENT`. An account with any
  staff role is refused, even if it also has a student record. Staff keep email + password.
- **Same refusal for everything:** unknown ID, deactivated student, disabled account, staff account,
  another academy's student → the same 401 and message.
- **Rate limited:** shares the per-client sign-in allowance with email sign-in (default 60 per 5 minutes; set lower
  with `LUMA_RATE_LIMIT_SIGN_IN_LIMIT`). Per-account lockout does not help here (there is no secret), so the
  per-client limit is the real control against enumeration.
- **Normal session security:** short-lived RS256 access token, rotating refresh token in an HttpOnly, Secure, SameSite
  cookie set by the BFF (never localStorage), logout and "sign out everywhere", session maximum lifetime.
- **Own data only:** a student session can only read the caller's own record, classes and attendance; every staff
  route answers 403 (tested: `StudentIdLoginHttpIntegrationTest`).
- **Email sign-in stays closed:** an account activated this way gets a random password nobody knows.

## Before switching it on in production

1. Written acceptance from the Academy of the risk above.
2. Lower the sign-in rate limit to what a real classroom needs.
3. Tell students not to share their ID, and how to report misuse.
4. Prefer a stronger option when possible: reuse the Academy app's own session (if it has a session-validation API),
   or a per-student PIN issued by the Academy. LUMA can add either without changing student data access.
