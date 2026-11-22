import { openSection } from "./support/ui";
import { scheduleClass } from "./support/classes";
import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
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
    p = (testInfo.project.name === "mobile" ? "gm" : "gd") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("An owner switches engagement on; a student consents and reacts; staff open and close a question", async ({ page }) => {
    await signInStaff(page, "owner-enrolled@example.invalid");
    await page.goto("/staff/attendance?section=settings");
    const settings = page.getByRole("region", { name: "ENGAGEMENT" });
    await settings.getByLabel("Engagement is on").check();
    await settings.getByRole("button", { name: "Save engagement settings" }).click();
    await expect(page.locator("#engagement-feedback")).toHaveText("Engagement is on. Students are asked for consent in their portal.");

    await signInStudent(page);
    const panel = page.getByRole("region", { name: "Reactions and questions" });
    await expect(panel).toContainText("LUMA records the reactions and answers");
    await panel.getByRole("button", { name: "I agree" }).click();
    await page.getByRole("link", { name: "Live Now" }).click();
    await page.getByRole("link", { name: "React and answer" }).click();
    await expect(page.getByRole("link", { name: "Live Now", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("link", { name: "Back to Live Now" })).toHaveAttribute("href", "/student/live");
    await expect(page.locator(".live-refresh")).toContainText("Updating every 20 seconds");
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
    await scheduleClass(page, { title: `Engagement class ${p}`, date: "2027-05-03", start: "14:00", end: "16:00", batch: `${C} · A · Batch A` });
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
    await openSection(ranking, "engagement ranking");
    await ranking.getByLabel("Show", { exact: true }).selectOption("100");
    await ranking.getByLabel("Over").selectOption("course");
    await ranking.getByRole("button", { name: "Show ranking" }).click();
    await expect(ranking).toContainText("No consenting students took part in this range.");
    const download = page.waitForEvent("download");
    await ranking.getByRole("link", { name: "Download CSV" }).click();
    expect((await download).suggestedFilename()).toMatch(/^engagement-ranking-\d{4}-\d{2}-\d{2}-to-\d{4}-\d{2}-\d{2}\.csv$/);
  });
});
