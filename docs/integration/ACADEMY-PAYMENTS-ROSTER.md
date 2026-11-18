# Academy database integration — classes, rosters and LUMA batches

**Status (ACAD-1):** read-only catalog, roster with names, class-to-batch links and roster sync are implemented and
tested against synthetic data. The live Academy connection is **not yet verified**: on 2026-11-18 Supabase's pooler
answered `user not found` for `luma_academy_readonly`, so the role setup script most likely rolled back (see
"Live connection" below).

## What LUMA reads

| Academy table (configurable) | Columns read | Why |
| --- | --- | --- |
| `public.payment` (`LUMA_ACADEMY_PAYMENTS_TABLE`) | `pcaid`, `type`, `class_type`, `deleted_at` | Which students are in which class |
| `public.student` (`LUMA_ACADEMY_STUDENT_TABLE`, empty = not read) | `pcaid`, `name`, `mail`, `deleted_at` | Show the student's name; match the student in Zoom by email |

Never read: payment amounts, dates, packages, NIC, address, date of birth, parents' details, and the Academy's
attendance table. The role the Academy created is granted exactly these columns, so the database itself enforces it.

### Rules (baseline until the Academy confirms otherwise)

- A row is a class membership when `lower(trim(type)) = 'class'` (`LUMA_ACADEMY_CLASS_PAYMENT_TYPE`), `deleted_at` is
  null, and both `class_type` and `pcaid` are non-blank. Labels are trimmed; student IDs are trimmed and upper-cased.
- A class's count is the number of **distinct students**, never payment rows. A student in two classes counts once in
  each.
- **Historical baseline:** every non-deleted class payment counts. Expiry (`expired_date`, `activation_date`,
  `duration`) is *not* applied yet — the Academy has not said whether expired payments still mean "in the class", and
  the read-only role is not granted those columns. Open question for the Academy.
- Labels such as `Admission` are listed like any other; staff simply do not link them to a batch. No label is hidden
  without an Academy rule.
- Student details are used only when exactly **one** live student row has that ID. None → shown by ID; two or more →
  ambiguous, name and email withheld.

## How it plugs into LUMA

LUMA's sessions, Zoom matching, live attendance and final reports all work per **batch**. Rather than rebuild them,
each Academy class is linked to one LUMA batch (`academy_class_links`, migration V27, LUMA's database only):

1. `GET /api/v1/academy/classes` — Academy classes, distinct-student counts, and the linked batch (owner/admin).
2. `PUT /api/v1/academy/classes/link` `{className, batchId}` — link a class to an active batch; one class per batch.
3. `POST /api/v1/academy/classes/sync` `{className}` — bring the batch in step with the class. Safe to repeat.
   - Students new to LUMA are created with student number = Academy ID and the Academy name (or the ID when unknown).
   - Their sign-in email is the Academy `mail` only when valid, unique in the roster (siblings often share one) and
     unused by another LUMA account; otherwise `<id>@students.academy.invalid`, which can never receive mail. Zoom
     matches students by email, so those students are resolved with the existing "who is this?" identity decisions.
   - Existing LUMA students (same number) are enrolled, never duplicated or overwritten.
   - Nobody is withdrawn automatically. Students staff withdrew stay withdrawn; students who left the Academy class are
     listed (`notInAcademyClass`) for staff to decide.
   - The audit trail records counts and the class label only — no IDs, names or emails.

Classes then get sessions and Zoom webinars exactly as before (including "Import classes from your Zoom webinars").

## Database isolation

The Academy connection is a private Hikari pool (read-only, 3 connections, 20 s query timeout), **not** a Spring
`DataSource` bean, so Flyway and JPA can never target it. Table names are checked against `schema.table` in lower
case and are the only text placed in SQL; values are bound parameters. Query failures return 503 without the
driver's message (it can name hosts and roles).

## Live connection

Configure only in the backend's ignored `.env` (never Git, chat, Vercel or CI):

```properties
LUMA_ACADEMY_PAYMENTS_ENABLED=true
LUMA_ACADEMY_PAYMENTS_JDBC_URL=jdbc:postgresql://<region>.pooler.supabase.com:5432/postgres?sslmode=require
LUMA_ACADEMY_PAYMENTS_USERNAME=luma_academy_readonly.<project-ref>
LUMA_ACADEMY_PAYMENTS_PASSWORD=<from the Academy>
LUMA_ACADEMY_STUDENT_TABLE=public.student
```

If the pooler says `user not found`, the role does not exist: the setup script runs in one transaction, so any
failing statement (for example a `GRANT` naming a column the student table does not have, such as `mail` or
`deleted_at`) undoes all of it. Ask the Academy to run the script again and check the final verification query lists
every granted column with `can_read = true`. If the student table lacks `mail` or `deleted_at`, the catalog query
must be adjusted before enabling the student table.

Do not upload the Academy's raw payment/student exports to Git. They contain personal and financial information.
