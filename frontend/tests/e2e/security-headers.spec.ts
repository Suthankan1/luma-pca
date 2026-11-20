import { test, expect } from "@playwright/test";

test.describe("Security headers", () => {
  for (const path of ["/", "/sign-in", "/staff", "/student", "/no-such-page"]) {
    test(`${path} cannot be framed and limits what the page may load`, async ({ request }) => {
      const response = await request.get(path, { maxRedirects: 0 });
      const headers = response.headers();
      expect(headers["x-frame-options"]).toBe("DENY");
      expect(headers["content-security-policy"]).toContain("frame-ancestors 'none'");
      expect(headers["content-security-policy"]).toContain("default-src 'self'");
      expect(headers["content-security-policy"]).not.toContain("unsafe-eval");
      expect(headers["x-content-type-options"]).toBe("nosniff");
      expect(headers["referrer-policy"]).toBe("same-origin");
      expect(headers["strict-transport-security"]).toContain("max-age=");
      expect(headers["x-powered-by"]).toBeUndefined();
    });
  }

  test("an unknown page shows LUMA's own not-found page", async ({ page }) => {
    const response = await page.goto("/no-such-page");
    expect(response?.status()).toBe(404);
    await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Go to LUMA home" })).toBeVisible();
  });

  test("signing in and using staff and student pages breaks no content-security rule", async ({ page }) => {
    const violations: string[] = [];
    page.on("console", message => { if (/Content.Security.Policy|Refused to/i.test(message.text())) violations.push(message.text()); });
    await page.goto("/sign-in");
    await page.fill("#email", "admin-enrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff$/);
    for (const path of ["/staff/courses", "/staff/students", "/staff/sessions", "/staff/attendance", "/staff/zoom"]) {
      await page.goto(path);
      await expect(page.locator("h1").first()).toBeVisible();
    }
    await page.context().clearCookies();
    await page.goto("/sign-in");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student$/);
    expect(violations).toEqual([]);
  });
});
