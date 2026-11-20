import { readFileSync } from "node:fs";
import { test, expect, devices, type Browser, type Page } from "@playwright/test";
import { openSection } from "../e2e/support/ui";

/**
 * Real stack, real browser: a staff member activates their invitation, signs in with
 * email and password, sets up a course, batch and student, invites and enrols the student,
 * the student activates and signs in, and a colleague added by the operator command activates and sees
 * everything staff see (two roles: STAFF and STUDENT; no staff page). Started by scripts/staff-ui-real-stack.sh.
 */
const adminEmail = "smoke.admin@example.invalid";
const adminInvite = process.env.LUMA_REAL_ADMIN_INVITE!;
const passphrase = process.env.LUMA_REAL_PASSPHRASE!;
const mailpit = process.env.LUMA_REAL_MAILPIT_URL!;

// The acceptance test below continues from the onboarding test's data and accounts.
test.describe.configure({ mode: "serial" });
let adminSignedIn = false;
let studentPassword = "";

type MailpitList = { messages: { ID: string; Subject: string; To: { Address: string }[] }[] };

/** The newest email to an address caught by Mailpit, waiting up to 10 s for it to arrive. */
async function newestEmailTo(address: string) {
  for (let attempt = 0; attempt < 40; attempt++) {
    const list = await (await fetch(`${mailpit}/api/v1/messages`)).json() as MailpitList;
    const found = list.messages.find(m => m.To.some(to => to.Address === address));
    if (found) {
      const message = await (await fetch(`${mailpit}/api/v1/message/${found.ID}`)).json() as { Text: string; From: { Address: string } };
      return { subject: found.Subject, from: message.From.Address, text: message.Text };
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`No email to ${address} arrived`);
}

/**
 * Classes are no longer scheduled in LUMA's screens (Zoom owns schedules). Steps that need a class with history create it
 * through the real API as the administrator (email and password), with manual scheduling switched on for this
 * test backend only (scripts/staff-ui-real-stack.sh).
 */
const apiBase = process.env.LUMA_REAL_API_URL!;
let apiToken = "";
async function adminToken() {
  if (apiToken) return apiToken;
  const login = await fetch(`${apiBase}/api/v1/auth/login`, { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: adminEmail, password: passphrase }) });
  expect(login.status, await login.clone().text()).toBe(200);
  apiToken = ((await login.json()) as { accessToken: string }).accessToken;
  return apiToken;
}
async function createClass(title: string, date: string, start: string, end: string, batch: string, webinar?: string) {
  const token = await adminToken();
  const get = async (path: string) => (await fetch(`${apiBase}${path}`, { headers: { Authorization: `Bearer ${token}` } })).json();
  const [courseCode, batchCode] = batch.split(" · ");
  const course = ((await get("/api/v1/courses")) as { id: string; code: string }[]).find(c => c.code === courseCode)!;
  const found = ((await get("/api/v1/batches")) as { id: string; courseId: string; code: string }[])
    .find(b => b.courseId === course.id && b.code === batchCode)!;
  const at = (time: string) => new Date(Date.parse(`${date}T${time}:00+05:30`)).toISOString();
  const created = await fetch(`${apiBase}/api/v1/class-sessions`, { method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ title, startsAt: at(start), endsAt: at(end), timeZone: "Asia/Colombo", batchIds: [found.id],
      zoomWebinarId: webinar ? webinar.replace(/\s/g, "") : null }) });
  expect(created.status, await created.clone().text()).toBe(201);
  return ((await created.json()) as { id: string }).id;
}

test("an administrator onboards a student end to end on the real stack", async ({ page, browser }) => {
  expect(adminInvite, "LUMA_REAL_ADMIN_INVITE").toBeTruthy();

  // 1. The administrator redeems their own invitation.
  await page.goto(`/activate#token=${adminInvite}`);
  await expect(page.locator("#token")).toHaveValue(adminInvite);
  await page.fill("#password", passphrase);
  await page.fill("#confirm", passphrase);
  await page.getByRole("button", { name: "Activate account" }).click();
  await expect(page).toHaveURL(/\/sign-in\?activated=1$/);

  // 2. Email and password open the staff portal directly.
  await page.fill("#email", adminEmail);
  await page.fill("#password", passphrase);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
  adminSignedIn = true;

  // 3. Course and batch through the real API.
  await page.goto("/staff/courses");
  await page.locator("#course-code").fill("real-phy");
  await page.locator("#course-title").fill("A/L Physics (real stack)");
  await page.getByRole("button", { name: "Create course" }).click();
  await expect(page.locator("#course-feedback")).toHaveText("Course REAL-PHY created.");
  await page.locator("#course-code").fill("REAL-PHY");
  await page.locator("#course-title").fill("Duplicate");
  await page.getByRole("button", { name: "Create course" }).click();
  await expect(page.locator("#course-feedback")).toHaveText("A course with this code already exists");
  await page.locator("#batch-course").selectOption({ label: "REAL-PHY · A/L Physics (real stack)" });
  await page.locator("#batch-code").fill("mon");
  await page.locator("#batch-name").fill("Monday evening");
  await page.locator("#batch-starts").fill("2027-01-10");
  await page.getByRole("button", { name: "Create batch" }).click();
  await expect(page.locator("#batch-feedback")).toHaveText("Batch MON created.");

  // 4. Student, invitation and enrolment.
  await page.goto("/staff/students");
  await openSection(page, "Add a student who is not in the Academy records");
  await page.locator("#student-number").fill("pca-real-001");
  await page.locator("#student-name").fill("Real Stack Student");
  await page.locator("#student-email").fill("real.student@example.invalid");
  await page.getByRole("button", { name: "Add student" }).click();
  await expect(page.locator("#student-feedback")).toHaveText("Student PCA-REAL-001 added. Open the record to send an invitation.");
  await page.getByRole("link", { name: "Open the new student’s record →" }).click();
  await expect(page.locator(".sub").first()).toContainText("Invitation pending");
  await page.getByRole("button", { name: "Create invitation link" }).click();
  const invitation = await page.getByLabel("Invitation link").inputValue();
  expect(invitation).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/activate#token=[A-Za-z0-9_-]{43}$/);

  await page.locator("#enroll-batch").selectOption({ label: "REAL-PHY · MON · Monday evening" });
  await page.locator("#enroll-date").fill("2027-01-12");
  await page.getByRole("button", { name: "Enrol", exact: true }).click();
  await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");
  const row = page.locator(".dir-course").filter({ hasText: "MON" });
  await row.getByLabel("Withdrawal date for MON").fill("2027-03-31");
  await row.getByRole("button", { name: "Withdraw from MON" }).click();
  await expect(row).toContainText("withdrawn 31 Mar 2027");
  await row.getByRole("button", { name: "Re-enrol in MON" }).click();
  await expect(row).not.toContainText("withdrawn");

  await page.getByRole("link", { name: /MON/ }).first().click();
  await expect(page.getByRole("region", { name: "ROSTER" }).locator(".dir-list")).toContainText("PCA-REAL-001 Real Stack Student");

  // 5. The student redeems the link in a separate browser session and signs in.
  const studentContext = await browser.newContext();
  const student = await studentContext.newPage();
  await student.goto(invitation);
  await student.fill("#password", passphrase);
  await student.fill("#confirm", passphrase);
  await student.getByRole("button", { name: "Activate account" }).click();
  await expect(student).toHaveURL(/\/sign-in\?activated=1$/);
  await student.fill("#email", "real.student@example.invalid");
  await student.fill("#password", passphrase);
  await student.getByRole("button", { name: "Sign in" }).click();
  await expect(student).toHaveURL(/\/student$/);
  await expect(student.getByRole("heading", { name: "Student portal" })).toBeVisible();
  await studentContext.close();

  // 6. The administrator sees the account as active.
  await page.goto("/staff/students");
  await expect(page.locator(".dir-course").filter({ hasText: "PCA-REAL-001" })).toContainText("Account active");

  // 6b. Two more students from a CSV file, enrolled in the same batch; the real API checks then imports.
  await page.getByRole("link", { name: "Import many students from a CSV file →" }).click();
  await page.locator("#import-file").setInputFiles({ name: "students.csv", mimeType: "text/csv", buffer: Buffer.from(
    "Student number,Full name,Email\npca-real-001,Clash,real.student@example.invalid\npca-real-002,\"Import, One\",real.import1@example.invalid\n") });
  await page.getByRole("button", { name: "Check file" }).click();
  await expect(page.getByRole("list", { name: "Problems in the file" }).getByRole("listitem")).toHaveText([
    "Line 2 · Email: An account with this email already exists",
    "Line 2 · Student number: A student with this student number already exists",
  ]);
  await page.locator("#import-file").setInputFiles({ name: "students.csv", mimeType: "text/csv", buffer: Buffer.from(
    "Student number,Full name,Email\npca-real-002,\"Import, One\",real.import1@example.invalid\npca-real-003,Import Two,real.import2@example.invalid\n") });
  await page.locator("#import-batch").selectOption({ label: "REAL-PHY · MON · Monday evening" });
  await page.locator("#import-date").fill("2027-01-12");
  await page.getByRole("button", { name: "Check file" }).click();
  await expect(page.locator("#import-feedback")).toHaveText("All 2 rows are ready. Nothing has been saved yet.");
  await page.getByRole("button", { name: "Import 2 students" }).click();
  await expect(page.locator("#import-feedback")).toContainText("Imported 2 students and enrolled them in REAL-PHY · MON · Monday evening.");
  await page.goto("/staff/students");
  await expect(page.locator(".dir-course").filter({ hasText: "PCA-REAL-002" })).toContainText("Import, One");
  await expect(page.locator(".dir-course").filter({ hasText: "PCA-REAL-003" })).toContainText("Invitation pending");
  await page.getByRole("search").getByLabel("Find a student").fill("IMPORT,");
  await page.getByRole("search").getByRole("button", { name: "Search" }).click();
  await expect(page.getByRole("region", { name: "STUDENT DIRECTORY" }).locator(".dir-list .dir-course")).toHaveText([/PCA-REAL-002\s*Import, One/]);
  await page.getByRole("search").getByLabel("Find a student").fill("real.student@example.invalid");
  await page.getByRole("search").getByRole("button", { name: "Search" }).click();
  await expect(page.getByRole("region", { name: "STUDENT DIRECTORY" }).locator(".dir-list .dir-course")).toHaveText([/PCA-REAL-001/]);

  // 6c. Invitation emails for the batch's students, sent by the background sender over SMTP.
  await page.goto("/staff/courses");
  await page.getByRole("link", { name: /MON/ }).first().click();
  const invitePanel = page.getByRole("region", { name: "INVITE BY EMAIL" });
  await openSection(invitePanel, "Email sign-in links");
  await invitePanel.getByRole("button", { name: "Check who will be emailed" }).click();
  await expect(invitePanel.locator("#bulk-invite-feedback")).toHaveText("2 students will be emailed. Nothing has been sent yet.");
  await invitePanel.getByRole("button", { name: "Email 2 invitations" }).click();
  await expect(invitePanel.locator("#bulk-invite-feedback")).toHaveText("2 invitations queued. They are sent in the background.");
  const bulkEmail = await newestEmailTo("real.import1@example.invalid");
  expect(bulkEmail.subject).toBe("Your Physics Cube Academy LUMA account");
  const bulkLink = /http:\/\/127\.0\.0\.1:\d+\/activate#token=[A-Za-z0-9_-]{43}/.exec(bulkEmail.text)?.[0];
  expect(bulkLink, bulkEmail.text).toBeTruthy();
  await newestEmailTo("real.import2@example.invalid");
  await expect(invitePanel.locator("#bulk-invite-progress")).toContainText("0 waiting · 2 sent · 0 failed", { timeout: 15000 });
  const importedContext = await browser.newContext();
  const imported = await importedContext.newPage();
  await imported.goto(bulkLink!);
  await imported.fill("#password", passphrase);
  await imported.fill("#confirm", passphrase);
  await imported.getByRole("button", { name: "Activate account" }).click();
  await expect(imported).toHaveURL(/\/sign-in\?activated=1$/);
  await importedContext.close();

  // 7. A colleague was added by the operator command (no staff page); the script passes their activation link.
  const instructorInvite = process.env.LUMA_REAL_COLLEAGUE_INVITE;
  expect(instructorInvite).toBeTruthy();
  await page.goto("/staff/team");
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();

  await page.goto("/staff/courses");
  await page.getByRole("link", { name: /MON/ }).first().click();
  await expect(page).toHaveURL(/\/staff\/batches\/[0-9a-f-]{36}$/);
  const rosterUrl = page.url();
  await expect(page.getByRole("region", { name: "ASSIGNED STAFF" })).toHaveCount(0);

  // 7b. A class for the batch (history: classes now come from Zoom runs, so it is created through the API), in Sri Lanka
  // time; the API stores UTC. The class page has no edit or cancel controls.
  const classId = await createClass("Real stack class", "2027-01-13", "18:00", "20:30", "REAL-PHY · MON · Monday evening", "812 3456 7890");
  await page.goto(`/staff/sessions/${classId}`);
  const classUrl = page.url();
  await expect(page.locator(".sub").first()).toHaveText("Wed 13 Jan 2027 · 18:00–20:30 (Asia/Colombo)");
  await expect(page.getByRole("region", { name: "EXPECTED STUDENTS" })).toContainText("PCA-REAL-001 Real Stack Student");
  // The seeded Zoom occurrence on this webinar at 18:20 was linked as soon as the class existed.
  const zoomLinks = page.getByRole("region", { name: "Zoom occurrences linked" });
  await expect(zoomLinks.getByRole("listitem").locator(".status")).toHaveText(["Linked automatically"]);
  await expect(page.getByRole("button", { name: /Save changes|Cancel class/ })).toHaveCount(0);

  // 7b'. The student sees the class and their batch in the student portal.
  const studentAgain = await browser.newContext();
  const portal = await studentAgain.newPage();
  await portal.goto("/sign-in?portal=student");
  await portal.fill("#email", "real.student@example.invalid");
  await portal.fill("#password", passphrase);
  await portal.getByRole("button", { name: "Sign in" }).click();
  await expect(portal).toHaveURL(/\/student$/);
  await portal.goto("/student?from=2027-01-11");
  await expect(portal.getByRole("region", { name: "MY CLASSES" }).getByRole("listitem", { name: "Real stack class" }))
    .toContainText("Wed 13 Jan 2027 · 18:00–20:30 (Asia/Colombo)");
  await expect(portal.getByRole("region", { name: "MY BATCHES" })).toContainText("from 12 Jan 2027");
  await studentAgain.close();

  // 7b''. LUMA no longer schedules classes or weekly series: the Classes page tracks Zoom links instead.
  await page.goto("/staff/sessions");
  await expect(page.locator("#schedule-title, #series-title")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "TRACK A ZOOM LINK" })).toBeVisible();

  // 7c. Zoom review against the seeded occurrences: scheduling the class already linked the one
  // on its webinar and time (re-running the matcher finds nothing more); the other occurrence is
  // decided by hand, then the decision changed.
  await page.goto("/staff/zoom");
  const auto = page.getByRole("listitem", { name: "Webinar 812 3456 7890 at 13 Jan 2027 18:20" });
  const manual = page.getByRole("listitem", { name: "Webinar 555 0001 1101 at 13 Jan 2027 09:30" });
  await expect(auto.locator(".status")).toHaveText("Linked automatically");
  await expect(manual.locator(".status")).toHaveText("Needs review");
  await page.getByRole("button", { name: "Run automatic linking" }).click();
  // The other one waiting is the seeded past run (9 Mar 2026), which step 12 links by creating its class.
  await expect(page.locator(".autolink-feedback")).toHaveText("Linked 0 occurrences; 2 still need review.");
  await expect(auto.locator(".status")).toHaveText("Linked automatically");
  await expect(auto.getByRole("link", { name: /^Real stack class, Wed 13 Jan 2027/ })).toBeVisible();
  await openSection(manual, "Decide");
  await manual.getByLabel("Class").selectOption({ label: "Wed 13 Jan 2027 · 18:00–20:30 · Real stack class" });
  await manual.getByLabel(/^Reason/).fill("Checked against the timetable");
  await manual.getByRole("button", { name: "Link to class" }).click();
  await expect(manual.locator(".status")).toHaveText("Linked by staff");
  await manual.getByText("Change decision").click();
  await manual.getByLabel(/^Reason/).fill("Staff rehearsal, not a class");
  await manual.getByRole("button", { name: "Not a class" }).click();
  await expect(manual.locator(".status")).toHaveText("Marked not a class");
  const failed = page.getByRole("region", { name: "FAILED EVENTS" });
  const receipt = failed.getByRole("listitem").filter({ hasText: "Seeded failure for the real-stack test" });
  await expect(receipt).toContainText("Participant left");
  await openSection(receipt, "Replay this event");
  await receipt.getByLabel(/^Reason/).fill("Parser fixed");
  await receipt.getByRole("button", { name: "Replay" }).click();
  await expect(failed).toContainText("No failed events.");
  // Processing is off for this run, so the replayed event waits in the queue.
  await expect(failed).toContainText("1 event is waiting to be processed.");

  // 8. The colleague activates, signs in with email and password and sees the whole staff portal.
  const colleagueContext = await browser.newContext();
  const colleague = await colleagueContext.newPage();
  await colleague.goto(instructorInvite!);
  await colleague.fill("#password", passphrase);
  await colleague.fill("#confirm", passphrase);
  await colleague.getByRole("button", { name: "Activate account" }).click();
  await expect(colleague).toHaveURL(/\/sign-in\?activated=1$/);
  await colleague.fill("#email", "real.instructor@example.invalid");
  await colleague.fill("#password", passphrase);
  await colleague.getByRole("button", { name: "Sign in" }).click();
  await expect(colleague).toHaveURL(/\/staff$/);
  await colleague.goto(rosterUrl);
  await expect(colleague.getByRole("region", { name: "ROSTER" }).locator(".dir-list")).toContainText("PCA-REAL-001 Real Stack Student");
  await colleague.goto("/staff/sessions?from=2027-01-10");
  await expect(colleague.getByRole("region", { name: "CLASSES" })).toContainText("Real stack class");
  await colleague.goto(classUrl);
  await expect(colleague.getByRole("region", { name: "EXPECTED STUDENTS" })).toContainText("PCA-REAL-001 Real Stack Student");
  for (const path of ["/staff/students", "/staff/zoom", "/staff/activity"]) {
    await colleague.goto(path);
    await expect(colleague.locator(".banner").filter({ hasText: /Only academy owners/ })).toHaveCount(0);
  }

  // 9. Blocking the colleague is the operator's runbook SQL; the script runs it after this test and checks sign-in fails.
  await colleagueContext.close();

  // 10. The student forgets their password: the link arrives by real SMTP and works once.
  const newPassphrase = `${passphrase} again`;
  studentPassword = newPassphrase;
  const resetContext = await browser.newContext();
  const forgetful = await resetContext.newPage();
  await forgetful.goto("/sign-in");
  await forgetful.getByRole("link", { name: "Forgot your password?" }).click();
  // Both pages have an #email field: wait for the new page before typing.
  await expect(forgetful.getByRole("heading", { name: "Forgot your password" })).toBeVisible();
  await forgetful.fill("#email", "real.student@example.invalid");
  await forgetful.getByRole("button", { name: "Email me a reset link" }).click();
  await expect(forgetful).toHaveURL(/\/forgot-password\?sent=1$/);
  const email = await newestEmailTo("real.student@example.invalid");
  expect(email.subject).toBe("Reset your LUMA password");
  expect(email.from).toBe("no-reply@example.invalid");
  const link = /http:\/\/127\.0\.0\.1:\d+\/reset-password#token=[A-Za-z0-9_-]{43}/.exec(email.text)?.[0];
  expect(link, email.text).toBeTruthy();
  await forgetful.goto(link!);
  await forgetful.fill("#password", newPassphrase);
  await forgetful.fill("#confirm", newPassphrase);
  await forgetful.getByRole("button", { name: "Change password" }).click();
  await expect(forgetful).toHaveURL(/\/sign-in\?reset=1$/);
  await forgetful.fill("#email", "real.student@example.invalid");
  await forgetful.fill("#password", passphrase);
  await forgetful.getByRole("button", { name: "Sign in" }).click();
  await expect(forgetful).toHaveURL(/\/sign-in\?error=invalid/);
  await forgetful.fill("#email", "real.student@example.invalid");
  await forgetful.fill("#password", newPassphrase);
  await forgetful.getByRole("button", { name: "Sign in" }).click();
  await expect(forgetful).toHaveURL(/\/student$/);
  // The link was single use.
  await forgetful.goto(link!);
  await forgetful.fill("#password", `${newPassphrase} once more`);
  await forgetful.fill("#confirm", `${newPassphrase} once more`);
  await forgetful.getByRole("button", { name: "Change password" }).click();
  await expect(forgetful).toHaveURL(/\/reset-password\?error=invalid$/);
  await resetContext.close();

  // An unknown address gets the same answer and no email.
  await page.goto("/forgot-password");
  await page.fill("#email", "nobody.here@example.invalid");
  await page.getByRole("button", { name: "Email me a reset link" }).click();
  await expect(page).toHaveURL(/\/forgot-password\?sent=1$/);

  // 11. Per-visitor limits: with one trusted proxy, the address it reports reaches the API.
  // This run allows 3 reset requests per hour per visitor.
  const askFrom = async (address: string) => {
    const visitor = await browser.newContext({ extraHTTPHeaders: { "X-Forwarded-For": address } });
    const tab = await visitor.newPage();
    const results: string[] = [];
    for (let i = 0; i < 4; i++) {
      await tab.goto("/forgot-password");
      await tab.fill("#email", `visitor${i}@example.invalid`);
      await tab.getByRole("button", { name: "Email me a reset link" }).click();
      await expect(tab).toHaveURL(/\/forgot-password\?(sent=1|error=limited)$/);
      results.push(new URL(tab.url()).search);
    }
    await visitor.close();
    return results;
  };
  expect(await askFrom("203.0.113.7")).toEqual(["?sent=1", "?sent=1", "?sent=1", "?error=limited"]);
  expect((await askFrom("203.0.113.8")).slice(0, 3)).toEqual(["?sent=1", "?sent=1", "?sent=1"]);

  // 12. Attendance end to end on the real API, from the seeded past run of webinar 833 0001 1100 (9 Mar 2026).
  await page.goto("/staff/courses");
  await page.locator("#batch-course").selectOption({ label: "REAL-PHY · A/L Physics (real stack)" });
  await page.locator("#batch-code").fill("past");
  await page.locator("#batch-name").fill("Past term");
  await page.locator("#batch-starts").fill("2026-01-01");
  await page.getByRole("button", { name: "Create batch" }).click();
  await expect(page.locator("#batch-feedback")).toHaveText("Batch PAST created.");
  await page.goto("/staff/students?q=PCA-REAL-001");
  await page.getByRole("link", { name: /PCA-REAL-001/ }).first().click();
  await page.locator("#enroll-batch").selectOption({ label: "REAL-PHY · PAST · Past term" });
  await page.locator("#enroll-date").fill("2026-01-05");
  await page.getByRole("button", { name: "Enrol", exact: true }).click();
  await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");
  const studentRecord = page.url();

  const pastClassId = await createClass("Past real class", "2026-03-09", "18:00", "20:00", "REAL-PHY · PAST · Past term", "833 0001 1100");
  await page.goto(`/staff/sessions/${pastClassId}`);
  const pastClass = page.url();

  // The run linked itself; its people are matched by sign-in email; the guest waits for review.
  const linkedRuns = page.getByRole("region", { name: "Zoom occurrences linked" });
  await expect(linkedRuns.getByRole("listitem").locator(".status")).toHaveText(["Linked automatically"]);
  await linkedRuns.getByRole("link", { name: "people" }).click();
  const people = page.getByRole("region", { name: "PEOPLE IN THIS OCCURRENCE" });
  await expect(people.getByRole("listitem", { name: /PCA-REAL-001/ }).locator(".status")).toHaveText("Student (sign-in email)");
  await expect(people.getByRole("listitem", { name: "real.guest@example.invalid" }).locator(".status")).toHaveText("Not matched");

  // The owner records the rule; the class is final from Zoom's report: 6 540 s of 7 200 s, first join 18:06 → present.
  await page.goto("/staff/attendance?section=settings");
  await page.locator("#policy-percent").fill("75");
  await page.locator("#policy-late").fill("10");
  await page.locator("#policy-description").fill("Approved for the real-stack run");
  await page.getByRole("button", { name: "Save rule" }).click();
  await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");
  await page.goto(pastClass);
  const fromReport = page.getByRole("region", { name: "ATTENDANCE FROM ZOOM’S REPORT" });
  await expect(fromReport.getByRole("listitem", { name: /Attendance PCA-REAL-001/ })).toContainText("1 h 49 min connected");
  await page.getByRole("button", { name: "Finalise attendance" }).click();
  const finalAttendance = page.getByRole("region", { name: "FINAL ATTENDANCE" });
  const pastRow = finalAttendance.getByRole("listitem", { name: /Attendance PCA-REAL-001/ });
  await expect(pastRow.locator(".status")).toHaveText("Present");

  await expect(pastRow.getByText("Correct", { exact: true })).toHaveCount(0);

  // CSV, staff history, and the student portal show the same final outcome.
  const csvDownload = page.waitForEvent("download");
  await finalAttendance.getByRole("link", { name: "Download CSV" }).click();
  const csvText = readFileSync(await (await csvDownload).path(), "utf8");
  expect(csvText).toContain(",PCA-REAL-001,");
  expect(csvText.trim().split("\r\n").find((l: string) => l.includes(",PCA-REAL-001,"))).toMatch(/,PRESENT$/);
  await page.goto(studentRecord);
  await expect(page.getByRole("region", { name: "ATTENDANCE HISTORY" }).getByRole("listitem", { name: "History Past real class" }).locator(".status")).toHaveText("Present");

  const finalStudent = await browser.newContext();
  const portalAgain = await finalStudent.newPage();
  await portalAgain.goto("/sign-in?portal=student");
  await portalAgain.fill("#email", "real.student@example.invalid");
  await portalAgain.fill("#password", newPassphrase);
  await portalAgain.getByRole("button", { name: "Sign in" }).click();
  await expect(portalAgain).toHaveURL(/\/student$/);
  await portalAgain.goto("/student/attendance");
  await expect(portalAgain.getByRole("region", { name: "FINAL ATTENDANCE AND APPEALS" }).getByRole("listitem", { name: "Attendance Past real class" })
    .locator(".status")).toHaveText("Present");
  await finalStudent.close();
  await page.goto("/staff/activity?category=classes");
  await expect(page.getByText("Finalised attendance of “Past real class”").first()).toBeVisible();
});

/** Signs the administrator in with email and password. */
async function signInAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", adminEmail);
  await page.fill("#password", passphrase);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

async function signInStudent(browser: Browser, options = {}) {
  const context = await browser.newContext(options);
  const page = await context.newPage();
  await page.goto("/sign-in?portal=student");
  await page.fill("#email", "real.student@example.invalid");
  await page.fill("#password", studentPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
  return page;
}

/** Wall-clock date and minute in Sri Lanka (UTC+05:30), as staff type them. */
function colombo(offsetMinutes: number) {
  const t = new Date(Date.now() + offsetMinutes * 60_000 + 330 * 60_000);
  return { date: t.toISOString().slice(0, 10), time: t.toISOString().slice(11, 16) };
}

async function noSidewaysScroll(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
}

test("user acceptance cases 11, 12, 13, 15 and 17 on the real stack", async ({ page, browser }) => {
  test.setTimeout(150_000);
  expect(adminSignedIn, "the onboarding test ran first").toBeTruthy();

  // UAT 11: the student appeals the corrected class; an administrator accepts it as Present.
  let student = await signInStudent(browser);
  await student.goto("/student/attendance");
  const record = student.getByRole("region", { name: "FINAL ATTENDANCE AND APPEALS" }).getByRole("listitem", { name: "Attendance Past real class" });
  await record.getByText("Appeal this").click();
  await record.getByLabel("What should the academy know?").fill("I was in class for the whole session; the note was for another day.");
  await record.getByRole("button", { name: "Send appeal" }).click();
  await expect(record).toContainText("appeal waiting for a decision");
  await signInAdmin(page);
  await page.goto("/staff/attendance");
  const appeal = page.getByRole("region", { name: "APPEALS" }).getByRole("listitem", { name: "Appeal PCA-REAL-001 Past real class" });
  await expect(appeal).toContainText("the note was for another day");
  await appeal.getByLabel("Outcome if accepted").selectOption({ label: "Present" });
  await appeal.getByLabel("Note to the student").fill("Checked against Zoom's report");
  await appeal.getByRole("button", { name: "Accept" }).click();
  await expect(appeal).toHaveCount(0);
  await student.reload();
  await expect(record.locator(".status")).toHaveText("Present");
  await expect(record).toContainText("appeal accepted");

  // UAT 12: the owner switches engagement on; the student agrees (named in rankings) and reacts during a running class.
  await page.goto("/staff/attendance?section=settings");
  const settings = page.getByRole("region", { name: "ENGAGEMENT" });
  await settings.getByLabel("Engagement is on").check();
  await settings.getByRole("button", { name: "Save engagement settings" }).click();
  await expect(page.locator("#engagement-feedback")).toHaveText("Engagement is on. Students are asked for consent in their portal.");
  const from = colombo(-15), to = colombo(60);
  if (to.date !== from.date) test.skip(true, "a class across midnight cannot be created in one day; run again later");
  const liveClassId = await createClass("Live acceptance class", from.date, from.time, to.time, "REAL-PHY · PAST · Past term");
  await page.goto(`/staff/sessions/${liveClassId}`);
  const liveClass = page.url();

  await student.goto("/student");
  const panel = student.getByRole("region", { name: "REACTIONS AND QUESTIONS" });
  await panel.getByLabel("Staff may also see my name in engagement rankings").check();
  await panel.getByRole("button", { name: "I agree" }).click();
  await expect(panel).toContainText("You take part");
  await student.getByRole("listitem", { name: "Live acceptance class" }).getByRole("link", { name: "Take part" }).click();
  await student.getByRole("button", { name: "I understand" }).click();
  await expect(student.locator("#reaction-feedback")).toHaveText("Sent.");
  await student.getByRole("button", { name: "Slow down" }).click();
  await expect(student.locator("#reaction-feedback")).toHaveText("Please wait a few seconds between reactions");
  const studentClass = student.url();

  // UAT 13: staff open a question for 3 minutes; the student answers once; staff close it early and see the counts.
  await page.goto(liveClass);
  const engagement = page.getByRole("region", { name: "ENGAGEMENT" });
  await expect(engagement.getByRole("listitem").filter({ hasText: "I understand" }).locator("strong")).toHaveText("1");
  await engagement.getByLabel("Question").fill("Which unit is force measured in?");
  await engagement.getByLabel("Options, one per line (2 to 5)").fill("Newton\nJoule\nWatt");
  await engagement.getByLabel("Open for (minutes)").fill("3");
  await engagement.getByRole("button", { name: "Open question" }).click();
  const question = engagement.getByRole("listitem", { name: "Question Which unit is force measured in?" });
  await expect(question.locator(".status").first()).toHaveText("Open");
  await student.goto(studentClass);
  await student.getByLabel("Newton").check();
  await student.getByRole("button", { name: "Send answer" }).click();
  await expect(student.getByText("You answered “Newton”.")).toBeVisible();
  await page.reload();
  await question.getByRole("button", { name: "Close now" }).click();
  await expect(question.locator(".status").first()).toHaveText("Closed");
  await expect(question.getByRole("listitem").filter({ hasText: "Newton" }).locator("strong")).toHaveText("1");
  await expect(question.getByRole("listitem").filter({ hasText: "Joule" }).locator("strong")).toHaveText("0");

  // UAT 15: the batch's top 10 names only the consenting, opted-in student.
  await page.getByRole("link", { name: "Roster →" }).first().click();
  const ranking = page.getByRole("region", { name: "ENGAGEMENT RANKING" });
  await openSection(ranking, "engagement ranking");
  await ranking.getByRole("button", { name: "Show ranking" }).click();
  await expect(ranking.getByRole("listitem", { name: "Rank 1" })).toContainText("PCA-REAL-001 Real Stack Student");
  await expect(ranking.getByRole("listitem", { name: /^Rank / })).toHaveCount(1);
  await student.context().close();

  // UAT 17: the portal, Zoom review and appeals pages work on a phone without sideways scrolling.
  student = await signInStudent(browser, devices["Pixel 7"]);
  await noSidewaysScroll(student);
  await student.goto(studentClass);
  await noSidewaysScroll(student);
  await student.context().close();
  const phone = await (await browser.newContext(devices["Pixel 7"])).newPage();
  await signInAdmin(phone);
  for (const path of ["/staff/zoom", "/staff/attendance", liveClass.replace(/^https?:\/\/[^/]+/, "")]) {
    await phone.goto(path);
    await noSidewaysScroll(phone);
  }
  await phone.context().close();
});
