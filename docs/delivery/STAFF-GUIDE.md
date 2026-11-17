# LUMA staff guide

Every staff account signs in with a password and an authenticator app code (set up at first sign-in; keep the recovery
codes). Change your password with **Change password** at the top of every staff page; you are signed out everywhere afterwards.

## Setting up
1. **Courses & batches**: create each course, then its batches (code, name, start date).
2. **Students**: add one, or **Import** a CSV (`studentNumber,fullName,email`), optionally enrolling everyone in a batch.
   Check first, then import; nothing is imported if any row has a problem.
3. **Invitations**: from a batch or the Students page, email invitations; failed emails are listed for follow-up.
4. **Staff**: add instructors and attendance officers, then assign them to batches. They see only those batches.

## Timetable and Zoom
- **Import classes from Zoom** (Classes → "Import classes from your Zoom webinars"): LUMA lists the academy's upcoming
  webinars from Zoom, every date of a recurring one. Tick the dates and the batches, keep or change the title, and press
  **Create classes**: each becomes a class with its webinar ID, so attendance links and fills in by itself. Dates that are
  already classes are shown and never created twice. Webinars with no fixed time in Zoom cannot be imported.
- **Classes**: or schedule a class or a weekly series by hand for one or more batches, with the Zoom **webinar ID**. Each
  student is expected once per class even if in two of its batches.
- **Zoom review**: each time a webinar runs it appears here and links itself to the class when exactly one class fits.
  Decide the others. **People in this occurrence** shows who joined; **Zoom accounts to match** lists emails that match no
  student: match them by student number, or mark them "not a student". A decision applies to every future webinar; **Remove** one
  made by mistake (with a reason). People who registered for the webinar are matched by their registration email even if
  they join from another account. The webinar host is shown as HOST and never counted as a student.
- **Check a webinar** (Zoom review → "Check a webinar by link or ID"): paste a join link (…/w/… or …/j/…) or the ID to
  see every past run, even ones LUMA never received live. Pick a run → **Check attendance** fetches Zoom's report and shows
  each person (matched to students by email) with time connected; **Link to class** makes it that class's attendance,
  ready to finalise. Registration links (…/register/WN_…) do not contain the ID: copy the ID from the webinar in Zoom.
- **Failed events**: replay one with a reason once its cause is fixed. A report Zoom never provides after six tries shows
  as "gave up"; fetch it again by hand from the occurrence.

## Attendance
- During and after class, the class page shows **provisional attendance**: time connected (rejoins counted once, only
  within the class time), first join and lateness. Not being seen is not proof of absence.
- About 15 minutes after the webinar ends, LUMA fetches **Zoom's participant report** (or press "Fetch Zoom's report").
- The owner records the academy's **attendance rule** on the Attendance page. Then press **Finalise attendance** on the
  class page: present / late / absent from Zoom's report, once per class.
- **Waiting to be finalised** (Attendance page): every ended class that is not final, with what it waits for (Zoom's
  report, or a Zoom run to link). **Finalise N ready classes** finalises them all at once.
- **Automatic finalisation** (owner, on the rule form): once Zoom's report is in, LUMA finalises each class within a few
  minutes in the owner's name; the activity log says "Automatically finalised". Corrections and recalculation still work.
- **Recalculate** a final class (with a reason) after a late report, a new Zoom decision or a rule change. Corrected
  outcomes are kept; students enrolled since are added.
- **Correct** a student's outcome (e.g. Excused) with a reason; the original outcome stays in the history. The student
  is emailed. Managers are emailed when a student appeals.
- **Appeals** from students appear on the Attendance page: accept (choose the outcome) or reject, with a note they see.
- **Attendance summary** on each batch page: every student's present / late / absent / excused and attendance rate for a
  period (this month by default), lowest rate first, so students who need a follow-up stand out.
- **Attendance by batch** on the Attendance page (owners, administrators and analysts): totals and rate per batch for a
  period, without student names. Analysts see only this and the rule.
- **Download CSV** on a class page, or for a batch and date range on the batch page. It is personal data: keep it private.

## Engagement (only if the academy has switched it on)
- Students who agreed can react during class and answer questions you open on the class page (1–10 minutes).
- The batch page shows a staff-only ranking for the batch or the whole course, with **Download CSV**; students are named
  only if they opted in.

## Oversight
- **Activity log**: every change to staff, students, classes, Zoom decisions, attendance and engagement, with filters.
- **Erase a student** (owners, on the student's or guardian's request): Students → the student → "Erase this student’s personal data". Type the student
  number to confirm. Their name, email and Zoom details are removed; attendance figures stay, anonymised. It cannot be undone.
- **Retention**: emails inside Zoom event records older than the retention period are removed each night.
