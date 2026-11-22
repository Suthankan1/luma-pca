import { expect, test, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in?portal=staff");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

// One shared mock backend: run in order, the first project only (the class is set up once).
test.describe.configure({ mode: "serial" });

test.describe("Academy classes", () => {
  test("Class Types syncs a payment name directly and keeps it selectable", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium", "Uses the desktop Academy fixtures");
    await signInStaff(page, "admin-enrolled@example.invalid");
    const headers = { Authorization: "Bearer fake-access-token.for-bff.e2e-test" };
    const api = mockApiBase() + "/api/v1";
    const course = await page.request.post(`${api}/courses`, { headers, data: { code: "ROSTER-PCA", title: "Roster fixture" } });
    expect(course.status()).toBe(201);
    const batch = await page.request.post(`${api}/batches`, { headers, data: {
      courseId: (await course.json()).id, code: "LEGACY", name: "Legacy roster", startsOn: "2026-01-01",
    } });
    expect(batch.status()).toBe(201);
    const session = await page.request.post(`${api}/class-sessions`, { headers, data: {
      title: "December Paper Class", startsAt: "2026-12-11T12:30:00Z", endsAt: "2026-12-11T13:30:00Z",
      timeZone: "Asia/Colombo", zoomWebinarId: "84400000003", batchIds: [(await batch.json()).id],
    } });
    expect(session.status()).toBe(201);
    await page.goto("/staff/class-types?month=2026-12");
    const list = page.getByRole("region", { name: "Class types for the selected month" });
    const row = list.getByRole("listitem", { name: "December Paper Class" });
    await expect(list.getByRole("listitem", { name: "DC" })).toHaveCount(0);
    await expect(row.getByText("Zoom link not tracked", { exact: true })).toBeVisible();
    const dropdown = row.getByLabel("Academy payment class");
    const sync = row.getByRole("button", { name: "Sync", exact: true });
    await expect(row.getByLabel("Zoom link or webinar ID")).toHaveCount(0);
    await expect(dropdown.locator("option")).toHaveText(["Select payment class", "A/L Physics 2027", "Admission", "DC"]);
    await dropdown.selectOption("DC");
    await row.getByRole("button", { name: "Track and sync" }).click();
    await expect(row.getByText("DC synced. 140 expected students per run.")).toBeVisible();
    await expect(row.getByText("Synced", { exact: true })).toBeVisible();
    await page.reload();
    await expect(dropdown).toHaveValue("DC");
    await dropdown.selectOption("Admission");
    await sync.click();
    await expect(row.getByText("Admission synced. 1 expected student per run.")).toBeVisible();
    await expect(dropdown).toHaveValue("Admission");
    await page.getByRole("link", { name: "Next month" }).click();
    await expect(page).toHaveURL(/month=2027-01$/);
    await expect(list.getByText("No class types scheduled for this month.")).toBeVisible();
  });

  test("A class from the Academy records is set up in one step and its students synced", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium", "Sets up shared mock data once");
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.goto("/staff/academy");
    await expect(page).toHaveURL(/\/staff\/academy$/);
    await expect(page.getByRole("heading", { name: "Academy classes", level: 1 })).toBeVisible();

    const physics = page.getByRole("listitem", { name: "A/L Physics 2027" });
    await expect(physics).toContainText("3 students");
    await expect(physics).toContainText("Not set up in LUMA yet.");
    await expect(page.getByRole("listitem", { name: "Admission" })).toContainText("1 student");

    await physics.getByRole("button", { name: "Set up in LUMA" }).click();
    await expect(physics.locator(".form-ok")).toHaveText(
      "Synced: 3 students in the Academy class · 3 new to LUMA · 3 enrolled now.");
    await expect(physics.getByRole("list", { name: "Needs attention" })).toContainText("1 without a single name");
    await expect(physics.getByRole("list", { name: "Needs attention" })).toContainText("2 new without a usable email");

    await page.reload();
    await expect(physics).toContainText("Set up");
    await expect(physics).toContainText(/last synced \d{1,2} \w{3} \d{4}/);
    await physics.getByRole("button", { name: "Sync students of A/L Physics 2027" }).click();
    await expect(physics.locator(".form-ok")).toHaveText(
      "Synced: 3 students in the Academy class · 0 enrolled now · 3 already enrolled.");

    await physics.getByRole("link", { name: /ACADEMY · A-L-PHYSICS-2027/ }).click();
    const roster = page.getByRole("region", { name: "ROSTER" });
    await expect(roster).toContainText("3 enrolled");
    await expect(roster).toContainText("PCA101");
    await expect(roster).toContainText("Amaya Perera");
  });
});
