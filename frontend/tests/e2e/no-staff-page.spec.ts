import { test, expect } from "@playwright/test";

/**
 * There is no staff page (owner decision 2026-11-20: one STAFF role; the operator adds and blocks staff, see
 * docs/delivery/runbooks/STAFF-ACCOUNTS.md).
 */
test("The staff portal has no Staff page or menu item", async ({ page }) => {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
  const menu = page.getByRole("complementary", { name: "Staff navigation" });
  await expect(menu.getByRole("link", { name: /^\W*Staff$/ })).toHaveCount(0);
  const sections = menu.getByRole("navigation", { name: "Staff sections" });
  await expect(sections.locator('a[href^="/staff"]')).toHaveText([/Home/, /Classes/, /Class Types/, /Students/]);
  await sections.getByRole("link", { name: "Students", exact: true }).click();
  await expect(sections.getByRole("link", { name: "Students", exact: true })).toHaveAttribute("aria-current", "page");
  expect((await page.goto("/staff/team"))?.status()).toBe(404);
  expect((await page.goto("/staff/team/00000000-0000-4000-8000-0000000000bb"))?.status()).toBe(404);
});
