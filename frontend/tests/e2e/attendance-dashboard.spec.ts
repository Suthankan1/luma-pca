import { expect, test, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in?portal=staff");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Attendance dashboard", () => {
  test("Staff see every student's time and attendance, filter, sort, and switch views", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.getByRole("link", { name: "Attendance reports" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard$/);
    const totals = page.getByRole("list", { name: "Totals" });
    await expect(totals.getByRole("listitem", { name: "Classes held: 2" })).toBeVisible();
    await expect(totals.getByRole("listitem", { name: "Students: 3" })).toBeVisible();

    const table = page.getByRole("table", { name: "Students" });
    const amaya = table.getByRole("row").filter({ hasText: "PCA101" });
    await expect(amaya).toContainText("Amaya Perera");
    await expect(amaya).toContainText("2 / 2");
    await expect(amaya).toContainText("3 h 10 min");
    await expect(amaya).toContainText("79%");
    await expect(amaya.getByRole("cell").last()).toHaveText("2");

    await page.getByLabel("Find a student").fill("kavin");
    await expect(table.locator("tbody tr")).toHaveCount(1);
    await page.getByLabel("Find a student").fill("");
    await page.getByRole("button", { name: /Never joined/ }).click();
    await expect(table.getByRole("row").filter({ hasText: "PCA103" })).toBeVisible();
    await expect(table.locator("tbody tr")).toHaveCount(1);
    await page.getByRole("button", { name: /^All/ }).click();
    await page.getByLabel("Sort by").selectOption("percent:desc");
    await expect(table.locator("tbody tr").first()).toContainText("PCA101");

    await page.getByRole("navigation", { name: "View" }).getByRole("link", { name: "Classes" }).click();
    const classes = page.getByRole("table", { name: "Classes" });
    await expect(classes.getByRole("row").filter({ hasText: "Waves and oscillations" })).toContainText("2 / 3");
    await expect(classes.getByRole("row").filter({ hasText: "Electric fields" })).toContainText("Final");

    await page.getByRole("navigation", { name: "View" }).getByRole("link", { name: "Grid" }).click();
    await expect(page.getByRole("table", { name: "Attendance grid" })).toContainText("PCA101");

    await page.getByRole("navigation", { name: "Period" }).getByRole("link", { name: "Last 7 days" }).click();
    await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=/);
    await expect(page.getByRole("list", { name: "Totals" }).getByRole("listitem", { name: "Classes held: 1" })).toBeVisible();
  });

  test("Opening a student shows their courses and each leave and rejoin", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.goto("/staff/dashboard");
    await page.getByRole("table", { name: "Students" }).getByRole("link", { name: "Amaya Perera" }).click();
    await expect(page).toHaveURL(/\/staff\/students\/[0-9a-f-]+\/attendance\?from=/);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Amaya Perera");
    await expect(page.getByRole("region", { name: "COURSES AND BATCHES" })).toContainText("PHY-2026");
    const waves = page.locator("details").filter({ hasText: "Waves and oscillations" });
    await expect(waves).toContainText("1 h 10 min");
    await expect(waves).toContainText("2 rejoins");
    await waves.locator("summary").click();
    const connections = waves.getByRole("list", { name: "Connections" });
    await expect(connections.getByRole("listitem")).toHaveCount(3);
    await expect(connections).toContainText("Away 10 min, then rejoined");
    await expect(connections.getByRole("listitem").first()).toContainText("25 min in class time");
  });

  test("A period over 92 days is refused with a clear message", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.goto("/staff/dashboard?from=2026-01-01&to=2026-09-01");
    await expect(page.locator(".session-error")).toContainText("at most 92 days");
  });
});

test.describe("My attendance (student)", () => {
  test("A student sees their own time in each class and every rejoin", async ({ page }) => {
    await page.context().clearCookies();
    await page.goto("/sign-in?portal=student");
    await page.fill("#email", "student@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student$/);
    await page.getByRole("link", { name: "View all attendance and appeals →" }).click();
    await expect(page.getByRole("heading", { name: "My attendance" })).toBeVisible();
    await expect(page.getByRole("list", { name: "My attendance" }).getByRole("listitem", { name: "Classes joined: 2 / 2" })).toBeVisible();
    await expect(page.getByRole("region", { name: "MY COURSES" })).toContainText("A/L Physics 2026");
    const waves = page.locator("details").filter({ hasText: "Waves and oscillations" });
    await waves.locator("summary").click();
    await expect(waves.getByRole("list", { name: "Connections" }).getByRole("listitem")).toHaveCount(3);
  });
});
