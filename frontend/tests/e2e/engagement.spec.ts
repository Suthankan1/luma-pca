import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/sign-in\/mfa/);
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

async function signInStudent(page: Page) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", "student@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
}

test.describe("Engagement with consent", () => {
  let p = "gd";
  test.beforeEach(({}, testInfo) => {
    p = testInfo.project.name === "mobile" ? "gm" : "gd";
  });

  test("An owner switches engagement on; a student consents and reacts; staff open and close a question", async ({ page }) => {
    await signInStaff(page, "owner-enrolled@example.invalid");
    await page.goto("/staff/attendance");
    const settings = page.getByRole("region", { name: "ENGAGEMENT" });
    await settings.getByLabel("Engagement is on").check();
    await settings.getByRole("button", { name: "Save engagement settings" }).click();
    await expect(page.locator("#engagement-feedback")).toHaveText("Engagement is on. Students are asked for consent in their portal.");

    await signInStudent(page);
    const panel = page.getByRole("region", { name: "REACTIONS AND QUESTIONS" });
    await expect(panel).toContainText("LUMA records the reactions and answers");
    await panel.getByRole("button", { name: "I agree" }).click();
    await expect(panel).toContainText("You take part");
    await page.getByRole("listitem", { name: "Waves and oscillations" }).getByRole("link", { name: "Take part" }).click();
    await page.getByRole("button", { name: "I understand" }).click();
    await expect(page.locator("#reaction-feedback")).toHaveText("Sent.");

    // Staff open a question on a class; the reaction above is on the student's own class, so counts are checked there.
    await page.context().clearCookies();
    await signInStaff(page, "admin-enrolled@example.invalid");
    const C = `ENG-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`eng-${p}`);
    await page.locator("#course-title").fill("Engagement course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Engagement course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await page.goto("/staff/sessions");
    await page.locator("#schedule-title").fill(`Engagement class ${p}`);
    await page.locator("#schedule-date").fill("2027-05-03");
    await page.locator("#schedule-start").fill("14:00");
    await page.locator("#schedule-end").fill("16:00");
    await page.locator("form", { has: page.locator("#schedule-title") }).getByLabel(`${C} · A · Batch A`, { exact: true }).check();
    await page.getByRole("button", { name: "Schedule class" }).click();
    await expect(page.locator("#schedule-feedback")).toHaveText(`Engagement class ${p} scheduled.`);
    await page.goto("/staff/sessions?from=2027-05-03");
    await page.getByRole("link", { name: new RegExp(`Engagement class ${p}`) }).first().click();
    const engagement = page.getByRole("region", { name: "ENGAGEMENT" });
    await engagement.getByLabel("Question").fill("Which unit is force measured in?");
    await engagement.getByLabel("Options, one per line (2 to 5)").fill("Newton\nNewton");
    await engagement.getByRole("button", { name: "Open question" }).click();
    await expect(page.locator("#pulse-feedback")).toHaveText("Give 2 to 5 different options, one per line, each up to 100 characters.");
    await engagement.getByLabel("Options, one per line (2 to 5)").fill("Newton\nJoule\nWatt");
    await engagement.getByRole("button", { name: "Open question" }).click();
    const question = engagement.getByRole("listitem", { name: "Question Which unit is force measured in?" });
    await expect(question.locator(".status").first()).toHaveText("Open");
    await question.getByRole("button", { name: "Close now" }).click();
    await expect(question.locator(".status").first()).toHaveText("Closed");

    // Rankings are staff-only and name only students who opted in.
    await page.getByRole("link", { name: "Roster →" }).first().click();
    const ranking = page.getByRole("region", { name: "ENGAGEMENT RANKING" });
    await ranking.getByLabel("Show", { exact: true }).selectOption("100");
    await ranking.getByRole("button", { name: "Show ranking" }).click();
    await expect(ranking).toContainText("No consenting students took part in this range.");
  });
});
