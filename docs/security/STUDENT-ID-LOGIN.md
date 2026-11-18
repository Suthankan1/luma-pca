# Student ID sign-in — Academy-requested, weak by design

**Status:** implemented (ACAD-2), **off by default**. Switch on only after the Academy accepts the risk in writing.

## What the Academy asked for

Students sign in with **username = student ID** and **password = the same student ID** (for example `PCA12345` /
`PCA12345`). No registration, invitation, password creation or second factor.

## Why this is not secure authentication

A student ID is an identifier, not a secret: it is printed on receipts and class lists, shared on WhatsApp, and often
sequential. Anyone who knows or guesses an ID can sign in as that student and see that student's classes, attendance
and Zoom links, and act as them (reactions, attendance appeals). LUMA must not describe this as secure, and it does not
protect staff accounts in any way.

## What LUMA does to limit the damage

- **Off unless switched on in both places:** `LUMA_STUDENT_ID_LOGIN_ENABLED=true` in the API (which also needs
  `LUMA_STUDENT_ID_LOGIN_ACADEMY_ID`, defaulting to the Zoom webhook academy) and in the web app. While off, the API
  answers 404 and the sign-in page shows only the email form.
- **Students only:** the session is opened only for an account whose **sole** role is `STUDENT`. An account with any
  staff role is refused, even if it also has a student record. Staff keep email + password + MFA.
- **Same refusal for everything:** unknown ID, wrong password, deactivated student, disabled account, staff account,
  another academy's student → the same 401 and message.
- **Rate limited:** shares the per-client sign-in allowance with email sign-in (default 60 per 5 minutes; set lower
  with `LUMA_RATE_LIMIT_SIGN_IN_LIMIT`). Per-account lockout does not help here (the password is the ID), so the
  per-client limit is the real control against enumeration.
- **Normal session security:** short-lived RS256 access token, rotating refresh token in an HttpOnly, Secure, SameSite
  cookie set by the BFF (never localStorage), logout and "sign out everywhere", session maximum lifetime.
- **Own data only:** a student session can only read the caller's own record, classes and attendance; every staff
  route answers 403 (tested: `StudentIdLoginHttpIntegrationTest`).
- **Email sign-in stays closed:** an account activated this way gets a random password nobody knows.
- **A student's own authenticator is honoured:** if a student added one, they are still asked for it.

## Before switching it on in production

1. Written acceptance from the Academy of the risk above.
2. Lower the sign-in rate limit to what a real classroom needs.
3. Tell students not to share their ID, and how to report misuse.
4. Prefer a stronger option when possible: reuse the Academy app's own session (if it has a session-validation API),
   or a per-student PIN issued by the Academy. LUMA can add either without changing student data access.
