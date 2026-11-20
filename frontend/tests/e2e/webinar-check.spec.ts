import { openSection } from "./support/ui";
import { scheduleClass } from "./support/classes";
import { test, expect } from "@playwright/test";

test("An administrator checks a past webinar run from its link and links it to the class", async ({ page }, testInfo) => {
  const p = (testInfo.project.name === "mobile" ? "wm" : "wd") + (testInfo.retry ? String(testInfo.retry) : "");
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);

  // The student whose Zoom email appears in the mock report (shared by both browser projects).
  await page.goto("/staff/students");
  await openSection(page, "Add a student who is not in the Academy records");
  await page.locator("#student-number").fill("LK-1");
  await page.locator("#student-name").fill("Lookup Student");
  await page.locator("#student-email").fill("lookup.student@example.invalid");
  await page.getByRole("button", { name: "Add student" }).click();
  await expect(page.locator("#student-feedback")).toHaveText(/Student LK-1 added|already exists/);

  // A class at the time of the older run: 10 Mar 2026, 09:00–11:00 Colombo.
  const C = `WEB-${p.toUpperCase()}`;
  await page.goto("/staff/courses");
  await page.locator("#course-code").fill(`web-${p}`);
  await page.locator("#course-title").fill("Webinar course");
  await page.getByRole("button", { name: "Create course" }).click();
  await page.locator("#batch-course").selectOption({ label: `${C} · Webinar course` });
  await page.locator("#batch-code").fill("a");
  await page.locator("#batch-name").fill("Batch A");
  await page.locator("#batch-starts").fill("2026-01-01");
  await page.getByRole("button", { name: "Create batch" }).click();
  await page.goto("/staff/sessions");
  await scheduleClass(page, { title: `Checked class ${p}`, date: "2026-03-10", start: "09:00", end: "11:00", batch: `${C} · A · Batch A` });

  await page.goto("/staff/zoom");
  await page.getByRole("link", { name: "Check a webinar by link or ID →" }).click();
  await page.getByLabel("Join link or webinar ID").fill("https://us06web.zoom.us/webinar/register/WN_abcdef");
  await page.getByRole("button", { name: "Find runs" }).click();
  await expect(page.locator(".session-error")).toContainText("Registration links");
  await page.getByLabel("Join link or webinar ID").fill("https://us06web.zoom.us/w/77700012345?tk=example");
  await page.getByRole("button", { name: "Find runs" }).click();
  const runs = page.getByRole("region", { name: "PAST RUNS" });
  await expect(runs).toContainText("Webinar 777 0001 2345 · Lookup revision");
  await runs.getByRole("link", { name: "10 Mar 2026 09:00" }).click();

  const attendance = page.getByRole("region", { name: "ATTENDANCE" });
  await attendance.getByRole("button", { name: /Check attendance|Fetch again/ }).click();
  const student = attendance.getByRole("listitem", { name: "LK-1 Lookup Student" });
  await expect(student).toContainText("1 h 09 min connected");
  await expect(student.locator(".status")).toHaveText("Student (sign-in email)");
  await expect(attendance.getByRole("listitem", { name: "lookup.guest@example.invalid" }).locator(".status")).toHaveText("Not matched");

  await attendance.getByLabel("Class").selectOption({ label: `Tue 10 Mar 2026 · 09:00–11:00 · Checked class ${p}` });
  await attendance.getByLabel(/^Reason/).fill("Checked from the webinar link");
  await attendance.getByRole("button", { name: "Link to class" }).click();
  await expect(attendance.getByRole("link", { name: "open the class" })).toBeVisible();
});
