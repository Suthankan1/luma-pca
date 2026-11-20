import { openSection } from "./support/ui";
import { scheduleClass } from "./support/classes";
import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Less manual work for staff", () => {
  let p = "ad";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "am" : "ad") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("A webinar is monitored by its ID alone; Zoom's schedule is read-only and an early start is live at once", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium", "One mock Zoom account: monitored once");
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.goto("/staff/webinars");
    await expect(page.getByRole("heading", { name: "Webinars", level: 1 })).toBeVisible();
    // Only one field: the webinar ID or link. No title, date, time or recurrence to type.
    const add = page.getByRole("region", { name: "MONITOR A WEBINAR" });
    await expect(add.getByRole("textbox")).toHaveCount(1);
    await expect(add.locator("input[type=date], input[type=time], select")).toHaveCount(0);

    await add.getByLabel("Zoom webinar ID or join link").fill("https://zoom.us/webinar/register/WN_abc");
    await add.getByRole("button", { name: "Monitor webinar" }).click();
    await expect(add.locator(".session-error")).toContainText("ID");
    await add.getByLabel("Zoom webinar ID or join link").fill("844 9999 9999");
    await add.getByRole("button", { name: "Monitor webinar" }).click();
    await expect(add.locator(".session-error")).toContainText("not in the academy’s Zoom account");

    await add.getByLabel("Zoom webinar ID or join link").fill("https://us02web.zoom.us/j/84400000001?pwd=x");
    await add.getByRole("button", { name: "Monitor webinar" }).click();
    await expect(add.locator(".form-ok")).toContainText("Monitoring “A/L Physics 2027”");

    // Zoom started it about two hours before its scheduled time: live now, from the actual start.
    await page.reload();
    const live = page.getByRole("listitem", { name: "Live A/L Physics 2027" });
    await expect(live).toContainText("Connected now12");
    await expect(live).toContainText(/Running for\s*25 min/);
    await expect(live.locator("dd").nth(1)).not.toHaveText(await live.locator("dd").nth(0).innerText());

    const card = page.getByRole("listitem", { name: "Webinar 844 0000 0001" });
    await expect(card).toContainText("A/L Physics 2027");
    await expect(card).toContainText("(Asia/Colombo)");
    await expect(card.locator(".occurrence-list li")).toHaveCount(3);
    await expect(card).toContainText("Academy class: A/L Physics 2027 (matches the Zoom title)");
    await openSection(card, "Run history");
    await expect(card.locator(".disclosure-hint").last()).toHaveText("2");
    await expect(card.getByRole("listitem").filter({ hasText: "Zoom report in" })).toHaveCount(1);
    for (const name of ["Save changes", "Schedule class", "Cancel class", "Create classes"]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }

    // A webinar with no fixed time in Zoom: nothing is invented.
    await add.getByLabel("Zoom webinar ID or join link").fill("84400000002");
    await add.getByRole("button", { name: "Monitor webinar" }).click();
    const office = page.getByRole("listitem", { name: "Webinar 844 0000 0002" });
    await expect(office).toContainText("Recurring, no fixed time in Zoom");
    await expect(office.locator(".class-note")).toContainText("Academy class unresolved");
    await openSection(office, "Manage webinar");
    await office.getByLabel("Academy class").selectOption("Admission");
    await office.getByRole("button", { name: "Save" }).click();
    await expect(office.locator(".form-ok")).toHaveText("Academy class set to “Admission”.");
    await office.getByRole("button", { name: "Stop monitoring Office hours" }).click();
    await expect(office.locator(".form-ok")).toContainText("The Zoom webinar is unchanged");
    await expect(office).toContainText("Not monitored");

    // The run's class is listed as live, with no invented end time.
    await page.goto("/staff/sessions");
    await expect(page.getByRole("listitem").filter({ hasText: "A/L Physics 2027" }).first()).toContainText("live since");
    await page.goto("/staff/webinars");
    await expect(page.locator(".live-refresh")).toContainText("while this page is open");

    // The live run's class shows Zoom's schedule beside the actual start.
    await live.getByRole("link", { name: "Live attendance →" }).click();
    const run = page.getByRole("region", { name: "ZOOM RUN" });
    await expect(run).toContainText("Live");
    await expect(run).toContainText("Zoom’s report");
    await expect(page.locator(".sub").first()).toContainText("Live now");
  });

  test("Ended classes wait in one list and are finalised together; the owner can switch on automatic finalisation", async ({ page }) => {
    await signInStaff(page, "owner-enrolled@example.invalid");
    await page.goto("/staff/attendance?section=settings");
    const rule = page.getByRole("region", { name: "ATTENDANCE RULE" });
    await page.locator("#policy-percent").fill("75");
    await page.locator("#policy-late").fill("10");
    await page.locator("#policy-description").fill("Approved at the staff meeting");
    await rule.getByLabel(/Finalise each class automatically/).check();
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");
    await page.reload();
    await expect(rule.getByLabel(/Finalise each class automatically/)).toBeChecked();
    await page.goto("/staff/attendance");
    await expect(page.getByRole("region", { name: "WAITING TO BE FINALISED" })).toContainText("Automatic finalisation is on");
    await page.goto("/staff/attendance?section=settings");
    await rule.getByLabel(/Finalise each class automatically/).uncheck();
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");

    // A class that ended yesterday, with a webinar ID, waits for finalisation.
    await signInStaff(page, "admin-enrolled@example.invalid");
    const yesterday = new Date(Date.now() - 86_400_000 + 5.5 * 3_600_000).toISOString().slice(0, 10);
    const C = `FIN-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`fin-${p}`);
    await page.locator("#course-title").fill("Finalise course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Finalise course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2026-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await page.goto("/staff/sessions");
    await scheduleClass(page, { title: `Ended class ${p}`, date: yesterday, start: "09:00", end: "10:00", batch: `${C} · A · Batch A`, webinar: "838 1234 5678" });

    await page.goto("/staff/attendance");
    const waiting = page.getByRole("region", { name: "WAITING TO BE FINALISED" });
    await expect(waiting.getByRole("listitem", { name: `Pending Ended class ${p}` })).toContainText("Ready to finalise");
    await waiting.getByRole("button", { name: /Finalise \d+ ready class/ }).click();
    await expect(page.locator("#finalise-all-feedback")).toContainText("Finalised");
    await page.reload();
    await expect(waiting.getByRole("listitem", { name: `Pending Ended class ${p}` })).toHaveCount(0);
  });
});
