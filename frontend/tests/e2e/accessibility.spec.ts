import AxeBuilder from "@axe-core/playwright";
import { test, expect, type Page } from "@playwright/test";

// Automated WCAG 2.1 A/AA checks (axe) on the main screens; serious and critical issues fail the test.
async function check(page: Page, name: string) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  const blocking = results.violations.filter(v => v.impact === "serious" || v.impact === "critical");
  expect(blocking.map(v => `${name}: ${v.id} (${v.impact}) ${v.nodes.slice(0, 3).map(n => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.fill("#code", "rec-recovery");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test("public pages have no serious accessibility problems", async ({ page }) => {
  for (const path of ["/sign-in", "/forgot-password", "/activate", "/reset-password"]) {
    await page.goto(path);
    await check(page, path);
  }
});

test("staff pages have no serious accessibility problems", async ({ page }) => {
  await signInAsAdmin(page);
  for (const path of ["/staff", "/staff/courses", "/staff/sessions", "/staff/students", "/staff/team", "/staff/attendance", "/staff/zoom",
    "/staff/zoom/check", "/staff/zoom/import", "/staff/activity", "/account/password"]) {
    await page.goto(path);
    await check(page, path);
  }
});

test("student pages have no serious accessibility problems", async ({ page }) => {
  await page.goto("/sign-in?portal=student");
  await page.fill("#email", "student@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student$/);
  await check(page, "/student");
});
