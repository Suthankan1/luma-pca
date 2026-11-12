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

test.describe("Weekly class series", () => {
  // The mock backend is shared across projects and specs: codes are unique per project.
  let p = "sd";
  test.beforeEach(({}, testInfo) => {
    p = testInfo.project.name === "mobile" ? "sm" : "sd";
  });

  test("An administrator previews a series, resolves an overlap, creates it and cancels the rest", async ({ page }) => {
    await signInAsAdmin(page);
    const P = p.toUpperCase();
    const label = `${P}-SER · ${P}-FEB · February batch`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`${p}-ser`);
    await page.locator("#course-title").fill("Series course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${P}-SER · Series course` });
    await page.locator("#batch-code").fill(`${p}-feb`);
    await page.locator("#batch-name").fill("February batch");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${P}-FEB created.`);

    // A single class already on Monday 8 February.
    await page.goto("/staff/sessions");
    const single = page.locator("form", { has: page.locator("#schedule-title") });
    await page.locator("#schedule-title").fill(`Revision ${p}`);
    await page.locator("#schedule-date").fill("2027-02-08");
    await page.locator("#schedule-start").fill("18:30");
    await page.locator("#schedule-end").fill("19:30");
    await single.getByLabel(label, { exact: true }).check();
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText(`Revision ${p} scheduled.`);

    // Mondays and Thursdays in February, skipping Thursday 4th: the 8th overlaps.
    const series = page.locator("form", { has: page.locator("#series-title") });
    await page.locator("#series-title").fill(`Theory ${p}`);
    await page.locator("#series-first").fill("2027-02-01");
    await page.locator("#series-last").fill("2027-02-28");
    await series.getByLabel("Mon", { exact: true }).check();
    await series.getByLabel("Thu", { exact: true }).check();
    await page.locator("#series-start").fill("18:00");
    await page.locator("#series-end").fill("20:00");
    await page.locator("#series-skip").fill("2027-02-04");
    await series.getByLabel(label, { exact: true }).check();
    await page.getByRole("button", { name: "Preview classes" }).click();
    await expect(page.locator("#series-feedback")).toHaveText(
      "One class overlaps a class already scheduled for these batches. Add those dates to “Dates to skip”, or change the existing class.");
    await expect(series.getByRole("list", { name: "Overlapping classes" }).getByRole("link"))
      .toHaveText(`8 Feb 2027 overlaps “Revision ${p}”, Mon 8 Feb 2027 · 18:30–19:30`);
    await expect(page.getByRole("button", { name: /^Create/ })).toHaveCount(0);

    // Skipping the 8th previews six classes; entries were kept after the refusal.
    await page.locator("#series-skip").fill("2027-02-04, 2027-02-08");
    await page.getByRole("button", { name: "Preview classes" }).click();
    await expect(page.locator("#series-feedback")).toHaveText("6 classes, 1 Feb 2027 – 25 Feb 2027. Nothing has been created yet.");
    await expect(series.getByRole("list", { name: "Classes in the series" }).getByRole("listitem")).toHaveText([
      "Mon 1 Feb 2027 · 18:00–20:00", "Thu 11 Feb 2027 · 18:00–20:00", "Mon 15 Feb 2027 · 18:00–20:00",
      "Thu 18 Feb 2027 · 18:00–20:00", "Mon 22 Feb 2027 · 18:00–20:00", "Thu 25 Feb 2027 · 18:00–20:00",
    ]);

    // Creating after changing a detail is refused until previewed again.
    await page.locator("#series-end").fill("19:45");
    await page.getByRole("button", { name: "Create 6 classes" }).click();
    await expect(page.locator("#series-feedback")).toHaveText("The details changed since the preview. Preview the classes again.");
    await page.getByRole("button", { name: "Preview classes" }).click();
    await page.getByRole("button", { name: "Create 6 classes" }).click();
    await expect(page.locator("#series-feedback")).toHaveText("Created 6 classes, 1 Feb 2027 – 25 Feb 2027.");
    await page.getByRole("link", { name: "Show the classes →" }).click();
    await expect(page).toHaveURL(/\/staff\/sessions\?from=2027-02-01$/);
    const rows = page.locator(".dir-course").filter({ hasText: `Theory ${p}` });
    await expect(rows).toHaveCount(2);
    await expect(rows.first()).toContainText("Mon 1 Feb 2027 · 18:00–19:45");

    // From the class on 11 February, move it and the rest of the series half an hour earlier.
    await page.goto("/staff/sessions?from=2027-02-01");
    await page.locator(".dir-course").filter({ hasText: `Theory ${p}` }).filter({ hasText: "11 Feb 2027" }).getByRole("link").click();
    await expect(page.locator("#series-edit-from")).toHaveValue("2027-02-11");
    await page.locator("#series-edit-start").fill("17:30");
    await page.locator("#series-edit-end").fill("19:30");
    await page.getByRole("button", { name: "Preview changes" }).click();
    await expect(page.locator("#series-edit-feedback")).toHaveText("5 classes will change. Nothing has been changed yet.");
    await expect(page.getByRole("list", { name: "Classes after the change" }).getByRole("listitem").first()).toHaveText("Thu 11 Feb 2027 · 17:30–19:30");
    await page.getByRole("button", { name: "Change 5 classes" }).click();
    await expect(page.locator("#series-edit-feedback")).toHaveText("Changed 5 classes from 11 Feb 2027. Zoom links follow the new time and webinar.");
    await expect(page.locator(".sub").first()).toHaveText("Thu 11 Feb 2027 · 17:30–19:30 (Asia/Colombo)");
    await page.goto("/staff/sessions?from=2027-02-01");
    await expect(page.locator(".dir-course").filter({ hasText: `Theory ${p}` }).filter({ hasText: "1 Feb 2027" }).first())
      .toContainText("Mon 1 Feb 2027 · 18:00–19:45", { timeout: 2000 });
    await page.goto(`/staff/activity?category=classes`);
    await expect(page.locator(".activity-list > li").filter({ hasText: "Changed 5 classes of a weekly series" }).first())
      .toContainText("From 11 Feb 2027; changed time 17:30–19:30");

    // From the class on 15 February, cancel it and the rest of the series.
    await page.goto("/staff/sessions?from=2027-02-15");
    await page.locator(".dir-course").filter({ hasText: `Theory ${p}` }).filter({ hasText: "15 Feb 2027" }).getByRole("link").click();
    await expect(page.locator("#series-cancel-from")).toHaveValue("2027-02-15");
    await page.getByRole("button", { name: "Cancel these classes" }).click();
    await expect(page.locator("#series-cancel-feedback")).toHaveText("4 classes cancelled from 15 Feb 2027.");
    await page.goto("/staff/sessions?from=2027-02-01");
    await expect(page.locator(".dir-course").filter({ hasText: `Theory ${p}` }).filter({ hasText: "11 Feb 2027" }).locator(".status"))
      .toHaveText("Scheduled");
    await page.goto("/staff/sessions?from=2027-02-15");
    await expect(page.locator(".dir-course").filter({ hasText: `Theory ${p}` }).locator(".status"))
      .toHaveText(["Cancelled", "Cancelled", "Cancelled", "Cancelled"]);
  });
});
