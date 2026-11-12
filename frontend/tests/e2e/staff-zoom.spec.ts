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
    await expect(page).toHaveURL(/\/staff$/);
    await page.goto("/staff/zoom");
    await expect(page.locator(".banner")).toContainText("Only academy owners and administrators review Zoom events.");
    await expect(page.getByRole("button", { name: "Run automatic linking" })).toHaveCount(0);
  });
});
