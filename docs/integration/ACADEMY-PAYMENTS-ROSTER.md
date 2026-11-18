# Academy payments integration — read-only class catalog

**Status:** Backend foundation only. No student login replacement, public API, frontend changes, live Academy data access or Zoom changes in this slice.

## Academy-approved source

The Academy's existing `public.payments` table is the source for class labels and student memberships. Its sample has `pcaid`, `type`, `class_type`, `deleted_at` and date/payment columns. LUMA reads **only** the following fields: `pcaid`, `type`, `class_type`, `deleted_at`. It must never access the Academy's existing attendance data.

- One unique nonblank `class_type` under `type = 'class'` becomes one class label. Spaces around the label are trimmed.
- Each class's count is `COUNT(DISTINCT TRIM(pcaid))`, not the number of payments. Deleted rows, blank IDs, blank class labels and `type = 'package'` are excluded.
- `studentIdsForClass` returns distinct student IDs for later reconciliation with LUMA's own Zoom attendance evidence. It uses a bound query parameter, not SQL concatenation.
- A payment record alone does **not** prove a student joined a Zoom webinar, that their payment is current, or that the Academy intended a particular `class_type` to be a teaching class (sample values include `Admission`). Rules for expired payments and non-class labels still require Academy confirmation.
- `payments` has **no student name, email or password column**. Those fields cannot be obtained using this table alone. Student names require a separately approved identity lookup API or narrowly scoped additional read permission.

## Database isolation

This optional connection is independent of LUMA's own PostgreSQL/Flyway datasource. A private Hikari pool is owned by the Academy payments catalog and is **not** registered as a Spring `DataSource` bean, avoiding any possibility of Flyway targeting it through datasource auto-configuration. It is disabled by default. LUMA continues storing its own attendance/Zoom records in its own database.

Configure the backend server's **local ignored** `.env` (never commit it):

```properties
LUMA_ACADEMY_PAYMENTS_ENABLED=true
LUMA_ACADEMY_PAYMENTS_JDBC_URL=jdbc:postgresql://YOUR-ACADEMY-POOLER:5432/YOUR-DB?sslmode=require
LUMA_ACADEMY_PAYMENTS_USERNAME=ACADEMY_READ_ONLY_ROLE
LUMA_ACADEMY_PAYMENTS_PASSWORD=SET_IN_SECRET_STORE
```

Use an Academy-approved role with `SELECT` permission on `public.payments` only, without insert/update/delete or schema privileges. Pool read-only mode is an additional application hint, **not** the database security boundary. Keep these credentials out of the browser, logs, source, CI artifacts and pull requests.

## Integration plan

1. **This slice:** optional read-only catalog backend and PostgreSQL integration tests with synthetic payments. It is not exposed over HTTP yet.
2. Resolve student name source, current-versus-expired payment eligibility, the `Admission` label, and the approved staff/student login trust boundary. The Academy requested student ID and password to be identical; this is trivially guessable and requires an explicit production risk decision. Do not bypass role-based authorization or expose classmates' private information.
3. Add narrowly authorized backend endpoints to list class labels/counts and class student IDs. Never put Supabase credentials in Next.js.
4. Map the approved class catalog to LUMA's existing webinar occurrences and attendance matching logic. Preserve existing Zoom credentials, webhook processing, report reconciliation, and the Academy's original attendance table unchanged.
5. Retire the unused manual-course/batch/student registration screens only after the Academy integration has equivalent verified workflows.

Do not upload the Academy's raw payment/student CSV files to Git. They contain personal and financial information.
