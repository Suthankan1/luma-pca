import { scheduleClass } from "./support/classes";
import { test, expect } from "@playwright/test";

test("A running class's page updates itself, and staff can pause it", async ({ page }, testInfo) => {
  const p = (testInfo.project.name === "mobile" ? "lm" : "ld") + (testInfo.retry ? String(testInfo.retry) : "");
  // Colombo is UTC+05:30; a class from 30 minutes ago to 90 minutes from now, on today's Colombo date.
  const colombo = (offsetMinutes: number) => new Date(Date.now() + (330 + offsetMinutes) * 60_000).toISOString();
  const start = colombo(-30), end = colombo(90);
  test.skip(start.slice(0, 10) !== end.slice(0, 10), "the class would cross midnight in Colombo");

  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);

  const C = `LIV-${p.toUpperCase()}`;
  await page.goto("/staff/courses");
  await page.locator("#course-code").fill(`liv-${p}`);
  await page.locator("#course-title").fill("Live course");
  await page.getByRole("button", { name: "Create course" }).click();
  await page.locator("#batch-course").selectOption({ label: `${C} · Live course` });
  await page.locator("#batch-code").fill("a");
  await page.locator("#batch-name").fill("Batch A");
  await page.locator("#batch-starts").fill("2026-01-01");
  await page.getByRole("button", { name: "Create batch" }).click();
  await expect(page.locator("#batch-feedback")).toHaveText("Batch A created.");

  await page.goto("/staff/sessions");
  await scheduleClass(page, { title: `Live class ${p}`, date: start.slice(0, 10), start: start.slice(11, 16), end: end.slice(11, 16), batch: `${C} · A · Batch A` });
  await page.goto(`/staff/sessions?from=${start.slice(0, 10)}`);
  await page.getByRole("link", { name: new RegExp(`Live class ${p}`) }).first().click();

  const notice = page.locator(".live-refresh");
  await expect(notice).toContainText("Updating every 30 seconds while the class runs");
  await notice.getByRole("button", { name: "Pause" }).click();
  await expect(notice.getByRole("button", { name: "Resume" })).toBeVisible();
});
