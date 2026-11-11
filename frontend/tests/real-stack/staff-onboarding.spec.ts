import { createHmac } from "node:crypto";
import { test, expect } from "@playwright/test";

/**
 * Real stack, real browser: an administrator activates their invitation, enrols an
 * authenticator, sets up a course, batch and student, invites and enrols the student,
 * the student activates and signs in, and an instructor is invited, assigned, sees the
 * roster and the batch's class, and loses both when unassigned. Started by scripts/staff-ui-real-stack.sh.
 */
const adminEmail = "smoke.admin@example.invalid";
const adminInvite = process.env.LUMA_REAL_ADMIN_INVITE!;
const passphrase = process.env.LUMA_REAL_PASSPHRASE!;

/** RFC 6238 code (SHA-1, 6 digits, 30 s) for a Base32 secret, as an authenticator app computes it. */
function totp(base32: string, at = Date.now()) {
  // RFC 4648 Base32: A–Z then 2–7.
  const alphabet = String.fromCharCode(...Array.from({ length: 26 }, (_, i) => 65 + i)) + "234567";
  let bits = "";
  for (const char of base32.replace(/\s/g, "").toUpperCase()) bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map(byte => parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30_000)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
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

  // 2. First sign-in enrols an authenticator; recovery codes are shown once.
  await page.fill("#email", adminEmail);
  await page.fill("#password", passphrase);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Set up authenticator" })).toBeVisible();
  const secret = (await page.locator("code.mfa-secret").innerText()).replace(/\s/g, "");
  await page.fill("#code", totp(secret));
  await page.getByRole("button", { name: "Verify and save" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa\/recovery-codes/);
  await page.getByLabel("I have safely saved or written down these recovery codes").check();
  await page.getByRole("button", { name: "Continue to staff portal" }).click();
  await expect(page).toHaveURL(/\/staff$/);

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

  // 7. The owner adds an instructor, invites them and assigns them to the batch.
  await page.goto("/staff/team");
  await page.locator("#staff-email").fill("real.instructor@example.invalid");
  await page.locator(".role-choices").getByLabel("Instructor", { exact: true }).check();
  await page.getByRole("button", { name: "Add staff member" }).click();
  await expect(page.locator("#staff-feedback")).toHaveText("real.instructor@example.invalid added. Open their record to send an invitation.");
  await page.getByRole("link", { name: "Open their record →" }).click();
  await page.getByRole("button", { name: "Create invitation link" }).click();
  const instructorInvite = await page.getByLabel("Invitation link").inputValue();
  expect(instructorInvite).toMatch(/\/activate#token=[A-Za-z0-9_-]{43}$/);

  await page.goto("/staff/courses");
  await page.getByRole("link", { name: /MON/ }).first().click();
  await expect(page).toHaveURL(/\/staff\/batches\/[0-9a-f-]{36}$/);
  const rosterUrl = page.url();
  await page.locator("#assign-email").fill("real.instructor@example.invalid");
  await page.getByRole("button", { name: "Assign" }).click();
  await expect(page.locator("#assign-feedback")).toHaveText("Assigned. They can open this roster from their next request.");

  // 7b. A class for the batch, entered in Sri Lanka time; the API stores UTC.
  await page.goto("/staff/sessions");
  await page.locator("#schedule-title").fill("Real stack class");
  await page.locator("#schedule-date").fill("2027-01-13");
  await page.locator("#schedule-start").fill("18:00");
  await page.locator("#schedule-end").fill("20:00");
  await page.getByLabel("REAL-PHY · MON · Monday evening", { exact: true }).check();
  await page.locator("#schedule-webinar").fill("812 3456 7890");
  await page.getByRole("button", { name: "Schedule class" }).click();
  await expect(page.locator("#schedule-feedback")).toHaveText("Real stack class scheduled.");
  await page.getByRole("link", { name: "Open the class →" }).click();
  await expect(page).toHaveURL(/\/staff\/sessions\/[0-9a-f-]{36}$/);
  const classUrl = page.url();
  await expect(page.locator(".sub").first()).toHaveText("Wed 13 Jan 2027 · 18:00–20:00 (Asia/Colombo)");
  await expect(page.getByRole("region", { name: "EXPECTED STUDENTS" })).toContainText("PCA-REAL-001 Real Stack Student");
  await page.locator("#edit-end").fill("20:30");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.locator("#edit-feedback")).toHaveText("Saved. Expected students follow the new date and batches.");
  await expect(page.locator(".sub").first()).toHaveText("Wed 13 Jan 2027 · 18:00–20:30 (Asia/Colombo)");

  // 8. The instructor activates, signs in without MFA (not an elevated role) and sees only their batch.
  const instructorContext = await browser.newContext();
  const instructor = await instructorContext.newPage();
  await instructor.goto(instructorInvite);
  await instructor.fill("#password", passphrase);
  await instructor.fill("#confirm", passphrase);
  await instructor.getByRole("button", { name: "Activate account" }).click();
  await expect(instructor).toHaveURL(/\/sign-in\?activated=1$/);
  await instructor.fill("#email", "real.instructor@example.invalid");
  await instructor.fill("#password", passphrase);
  await instructor.getByRole("button", { name: "Sign in" }).click();
  await expect(instructor).toHaveURL(/\/staff$/);
  await instructor.goto("/staff/courses");
  const mine = instructor.getByRole("region", { name: "YOUR BATCHES" });
  await expect(mine).toContainText("MON Monday evening");
  await mine.getByRole("link", { name: "Roster →" }).click();
  await expect(instructor.getByRole("region", { name: "ROSTER" }).locator(".dir-list")).toContainText("PCA-REAL-001 Real Stack Student");
  await instructor.goto("/staff/sessions?from=2027-01-10");
  await expect(instructor.getByRole("region", { name: "CLASSES" })).toContainText("Real stack class");
  await expect(instructor.getByRole("button", { name: "Schedule class" })).toHaveCount(0);
  await instructor.goto(classUrl);
  await expect(instructor.getByRole("region", { name: "EXPECTED STUDENTS" })).toContainText("PCA-REAL-001 Real Stack Student");
  await expect(instructor.getByRole("button", { name: "Save changes" })).toHaveCount(0);
  await instructor.goto("/staff/students");
  await expect(instructor.locator(".banner")).toContainText("Only academy owners and administrators manage student records.");
  await instructor.goto("/staff/team");
  await expect(instructor.locator(".banner")).toContainText("Only academy owners and administrators manage staff accounts.");

  // 9. The owner removes the assignment; the instructor loses the roster on their next request.
  await page.goto(rosterUrl);
  await page.getByRole("button", { name: "Remove real.instructor@example.invalid as Instructor" }).click();
  await expect(page.getByText("No one is assigned yet.")).toBeVisible();
  expect((await instructor.goto(rosterUrl))?.status()).toBe(404);
  await instructor.goto("/staff/courses");
  await expect(instructor.getByText("You are not assigned to any batch yet.")).toBeVisible();
  expect((await instructor.goto(classUrl))?.status()).toBe(404);
  await instructorContext.close();
});
