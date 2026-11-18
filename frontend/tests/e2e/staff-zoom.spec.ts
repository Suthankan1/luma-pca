import { test, expect, type Page } from "@playwright/test";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa/);
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Zoom review", () => {
  // The mock seeds Zoom data per browser project: "1" for desktop (1 Mar 2027), "2" for mobile (2 Mar 2027).
  let p = "d";
  let n = "1";
  test.beforeEach(({}, testInfo) => {
    p = testInfo.project.name === "mobile" ? "m" : "d";
    n = p === "m" ? "2" : "1";
  });

  test("Occurrences are linked automatically or decided by an administrator, with a recorded reason", async ({ page }) => {
    await signInAsAdmin(page);
    const C = `ZOO-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`zoo-${p}`);
    await page.locator("#course-title").fill("Zoom course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Zoom course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("Batch A created.");

    // A class on the webinar of the seeded occurrence, 18:00–20:00 Colombo (12:30–14:30 UTC).
    await page.goto("/staff/sessions");
    await page.locator("#schedule-title").fill(`Zoom class ${p}`);
    await page.locator("#schedule-date").fill(`2027-03-0${n}`);
    await page.locator("#schedule-start").fill("18:00");
    await page.locator("#schedule-end").fill("20:00");
    await page.locator("form", { has: page.locator("#schedule-title") }).getByLabel(`${C} · A · Batch A`, { exact: true }).check();
    await page.locator("#schedule-webinar").fill(`987 6543 210${n}`);
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText(`Zoom class ${p} scheduled.`);

    await page.getByRole("link", { name: /Zoom review$/ }).first().click();
    await expect(page).toHaveURL(/\/staff\/zoom$/);
    const auto = page.getByRole("listitem", { name: `Webinar 987 6543 210${n} at ${n} Mar 2027 18:05` });
    const manual = page.getByRole("listitem", { name: `Webinar 555 0001 110${n} at ${n} Mar 2027 09:30` });
    await expect(manual.locator(".status")).toHaveText("Needs review");

    await page.getByRole("button", { name: "Run automatic linking" }).click();
    await expect(page.locator(".autolink-feedback")).toHaveText(/^Linked \d+ occurrences?; \d+ still needs? review\.$/);
    await expect(auto.locator(".status")).toHaveText("Linked automatically");
    await expect(auto.getByRole("link", { name: new RegExp(`^Zoom class ${p}, `) })).toBeVisible();
    await expect(manual.locator(".status")).toHaveText("Needs review");

    // A manual link needs a reason; a refused one keeps what was typed.
    const weekday = n === "1" ? "Mon" : "Tue";
    await manual.getByLabel("Class").selectOption({ label: `${weekday} ${n} Mar 2027 · 18:00–20:00 · Zoom class ${p}` });
    await manual.getByLabel(/^Reason/).fill("no");
    await manual.getByRole("button", { name: "Link to class" }).click();
    await expect(manual.locator(".decision-feedback")).toHaveText("Give a reason of 3 to 300 characters. Don’t include names or emails.");
    await expect(manual.getByLabel(/^Reason/)).toHaveValue("no");
    await manual.getByLabel(/^Reason/).fill("Checked against the timetable");
    await manual.getByRole("button", { name: "Link to class" }).click();
    await expect(manual.locator(".status")).toHaveText("Linked by an administrator");
    await expect(manual.getByRole("link", { name: new RegExp(`^Zoom class ${p}, `) })).toBeVisible();

    // A decision can be changed; it is never overridden automatically.
    await manual.getByText("Change decision").click();
    await manual.getByLabel(/^Reason/).fill("Staff rehearsal, not a class");
    await manual.getByRole("button", { name: "Not a class" }).click();
    await expect(manual.locator(".status")).toHaveText("Marked not a class");
    await page.getByRole("button", { name: "Run automatic linking" }).click();
    await expect(page.locator(".autolink-feedback")).toBeVisible();
    await expect(manual.locator(".status")).toHaveText("Marked not a class");

    await page.getByRole("link", { name: "Decided" }).click();
    await expect(page).toHaveURL(/view=decided$/);
    await expect(manual).toBeVisible();
    await page.getByRole("link", { name: "Needs review" }).click();
    await expect(manual).toHaveCount(0);
    await expect(auto).toHaveCount(0);

    // The class page lists its link; moving the class to another day sends the occurrence back to review.
    await page.goto("/staff/zoom?view=decided");
    await auto.getByRole("link", { name: new RegExp(`^Zoom class ${p}, `) }).click();
    const linked = page.getByRole("region", { name: "Zoom occurrences linked" });
    await expect(linked.getByRole("listitem")).toHaveCount(1);
    await expect(linked.getByRole("listitem")).toContainText(`Started ${n} Mar 2027 18:05 · 6 events`);
    await expect(linked.getByRole("listitem").locator(".status")).toHaveText("Linked automatically");
    await page.locator("#edit-date").fill(`2027-03-1${n}`);
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.locator("#edit-feedback")).toHaveText("Saved. Expected students and automatic Zoom links follow the new date, time and webinar.");
    await expect(linked).toContainText("None yet.");
    await page.goto("/staff/zoom");
    await expect(auto.locator(".status")).toHaveText("Needs review");
  });

  test("Zoom participants are matched to students by sign-in email, or by an administrator with a reason", async ({ page }) => {
    await signInAsAdmin(page);
    const P = p.toUpperCase();
    // A student whose sign-in email is the one Zoom reports for them, and one with another Zoom account.
    await page.goto("/staff/students");
    for (const [number, name, email] of [[`ZM-${P}-1`, "Zoom Student", `zoom.student${n}@example.invalid`],
      [`ZM-${P}-2`, "Guest Student", `guest.student${n}@example.invalid`]]) {
      await page.locator("#student-number").fill(number);
      await page.locator("#student-name").fill(name);
      await page.locator("#student-email").fill(email);
      await page.getByRole("button", { name: "Add student" }).click();
      await expect(page.locator("#student-feedback")).toHaveText(`Student ${number} added. Open the record to send an invitation.`);
    }

    await page.goto("/staff/zoom");
    const toMatch = page.getByRole("region", { name: "ZOOM ACCOUNTS TO MATCH" });
    await expect(toMatch.getByRole("listitem", { name: `Zoom account zoom.student${n}@example.invalid` })).toHaveCount(0);
    await expect(toMatch.getByRole("listitem", { name: `Zoom account teacher${n}@example.invalid` })).toHaveCount(0);
    const guest = toMatch.getByRole("listitem", { name: `Zoom account zoom.guest${n}@example.invalid` });
    await expect(guest).toContainText(`Guest Viewer ${n}`);

    // The occurrence's people: the student matched by sign-in email, the guest waiting, the host apart.
    await page.getByRole("listitem", { name: `Webinar 555 0001 110${n} at ${n} Mar 2027 09:30` })
      .getByRole("link", { name: "People in this occurrence" }).click();
    await expect(page).toHaveURL(/\/staff\/zoom\/participants\?occurrence=man%2F/);
    const people = page.getByRole("region", { name: "PEOPLE IN THIS OCCURRENCE" });
    const student = people.getByRole("listitem", { name: `ZM-${P}-1 Zoom Student` });
    await expect(student.locator(".status")).toHaveText("Student (sign-in email)");
    await expect(student).toContainText("2 visits");
    await expect(student).toContainText("no leave received yet");
    await expect(people.getByRole("listitem", { name: `teacher${n}@example.invalid` }).locator(".status")).toHaveText("Host");
    const unmatched = people.getByRole("listitem", { name: `zoom.guest${n}@example.invalid` });
    await expect(unmatched.locator(".status")).toHaveText("Not matched");

    // A wrong number is refused and keeps what was typed; then the guest account is matched.
    await unmatched.getByLabel("Student number").fill("NOPE-1");
    await unmatched.getByLabel(/^Reason/).fill("Confirmed with the student");
    await unmatched.getByRole("button", { name: "Match to student" }).click();
    await expect(unmatched.locator(".identity-feedback")).toHaveText("No student has the number NOPE-1.");
    await expect(unmatched.getByLabel(/^Reason/)).toHaveValue("Confirmed with the student");
    await unmatched.getByLabel("Student number").fill(`zm-${p}-2`);
    await unmatched.getByRole("button", { name: "Match to student" }).click();
    const matched = people.getByRole("listitem", { name: `ZM-${P}-2 Guest Student` });
    await expect(matched.locator(".status")).toHaveText("Student (matched by staff)");
    await expect(matched).toContainText(`shown in Zoom as “Guest Viewer ${n}”`);

    // A decision can be removed again, with a reason; the account then waits for review.
    await matched.getByText("Change match").click();
    await matched.getByLabel(/^Reason/).last().fill("Matched the wrong student");
    await matched.getByRole("button", { name: "Remove decision" }).click();
    await expect(people.getByRole("listitem", { name: `zoom.guest${n}@example.invalid` }).locator(".status")).toHaveText("Not matched");
    const again = people.getByRole("listitem", { name: `zoom.guest${n}@example.invalid` });
    await again.getByLabel("Student number").fill(`zm-${p}-2`);
    await again.getByLabel(/^Reason/).fill("Confirmed with the student");
    await again.getByRole("button", { name: "Match to student" }).click();
    await expect(people.getByRole("listitem", { name: `ZM-${P}-2 Guest Student` }).locator(".status")).toHaveText("Student (matched by staff)");

    await page.goto("/staff/zoom");
    await expect(guest).toHaveCount(0);
    await page.goto("/staff/activity?category=zoom");
    await expect(page.getByText(`Matched a Zoom account to student ZM-${P}-2`).first()).toBeVisible();

    // Provisional attendance: ZM-…-1 enrolled in the class's batch, the occurrence linked to the class.
    const C = `ATT-${P}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`att-${p}`);
    await page.locator("#course-title").fill("Attendance course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Attendance course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText("Batch A created.");
    await page.goto(`/staff/students?q=ZM-${P}-1`);
    await page.getByRole("link", { name: new RegExp(`ZM-${P}-1`) }).first().click();
    await page.locator("#enroll-batch").selectOption({ label: `${C} · A · Batch A` });
    await page.locator("#enroll-date").fill("2027-01-05");
    await page.getByRole("button", { name: "Enrol", exact: true }).click();
    await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");

    await page.goto("/staff/sessions");
    await page.locator("#schedule-title").fill(`Attendance class ${p}`);
    await page.locator("#schedule-date").fill(`2027-03-0${n}`);
    await page.locator("#schedule-start").fill("09:00");
    await page.locator("#schedule-end").fill("11:00");
    await page.locator("form", { has: page.locator("#schedule-title") }).getByLabel(`${C} · A · Batch A`, { exact: true }).check();
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText(`Attendance class ${p} scheduled.`);

    await page.goto("/staff/zoom");
    const occurrence = page.getByRole("listitem", { name: `Webinar 555 0001 110${n} at ${n} Mar 2027 09:30` });
    await expect(occurrence).toBeVisible();
    const select = occurrence.locator("select[name='sessionId']");
    if (!await select.isVisible()) {
      await occurrence.getByText("Change decision").click();
      await expect(select).toBeVisible();
    }
    const weekday = n === "1" ? "Mon" : "Tue";
    await select.selectOption({ label: `${weekday} ${n} Mar 2027 · 09:00–11:00 · Attendance class ${p}` });
    await occurrence.getByLabel(/^Reason/).fill("Checked against the timetable");
    await occurrence.getByRole("button", { name: "Link to class" }).click();
    await expect(occurrence.locator(".status")).toHaveText("Linked by an administrator");
    await occurrence.getByRole("link", { name: new RegExp(`^Attendance class ${p}, `) }).click();

    const attendance = page.getByRole("region", { name: "PROVISIONAL ATTENDANCE" });
    await expect(attendance).toContainText("not proof of absence");
    const expectedRow = attendance.getByRole("listitem", { name: `Attendance ZM-${P}-1 Zoom Student` });
    await expect(expectedRow.locator(".status")).toHaveText("Seen");
    await expect(expectedRow).toContainText("29 min connected");
    await expect(expectedRow).toContainText("first joined 2 Mar 2027 09:31".replace("2 Mar", `${n} Mar`));
    await expect(expectedRow).toContainText("31 min after the start");
    await expect(expectedRow).toContainText("no leave received yet");
    const extraRow = attendance.getByRole("listitem", { name: `Attendance ZM-${P}-2 Guest Student` });
    await expect(extraRow).toContainText("not expected at this class");

    // After the webinar, Zoom's participant report replaces the live events.
    await page.goto("/staff/zoom");
    await expect(occurrence.locator(".report-state")).toHaveText("Zoom report will be fetched automatically");
    await occurrence.getByRole("button", { name: "Fetch Zoom’s report" }).click();
    await expect(occurrence.locator(".report-feedback")).toHaveText("Report fetched: 4 join sessions.");
    await expect(occurrence.locator(".report-state")).toContainText("Zoom report fetched");
    await expect(occurrence.getByRole("button", { name: "Fetch Zoom’s report again" })).toBeVisible();
    await occurrence.getByRole("link", { name: new RegExp(`^Attendance class ${p}, `) }).click();
    const reconciled = page.getByRole("region", { name: "ATTENDANCE FROM ZOOM’S REPORT" });
    await expect(reconciled).toContainText("From Zoom’s participant report, fetched after the webinar ended");
    await expect(reconciled).toContainText("No present or absent decision is made");
  });

  test("A failed event is replayed with a reason", async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto("/staff/zoom");
    const receipt = page.getByRole("listitem", { name: `Participant joined received ${n} Mar 2027 18:06` });
    await expect(receipt).toContainText(`Payload has no occurrence UUID (case ${n})`);
    await receipt.getByLabel(/^Reason/).fill("Parser fixed");
    await receipt.getByRole("button", { name: "Replay" }).click();
    await expect(receipt).toHaveCount(0);
    await expect(page.getByRole("region", { name: "FAILED EVENTS" })).toContainText(/\d+ events? (is|are) waiting to be processed/);
  });

  test("Only owners and administrators review Zoom events", async ({ page }) => {
    await page.goto("/sign-in");
    await page.fill("#email", "instructor@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await page.fill("#code", "rec-recovery");
    await page.getByRole("button", { name: "Verify" }).click();
    await expect(page).toHaveURL(/\/staff$/);
    await page.goto("/staff/zoom");
    await expect(page.locator(".banner")).toContainText("Only academy owners and administrators review Zoom events.");
    await expect(page.getByRole("button", { name: "Run automatic linking" })).toHaveCount(0);
  });
});
