import { expect, test, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function signIn(page: Page, portal: "staff" | "student") {
  await page.goto(`/sign-in?portal=${portal}`);
  if (portal === "student") {
    await page.getByLabel("Registration ID").fill("PCA001");
    await page.getByRole("button", { name: "View my attendance" }).click();
  } else {
    await page.fill("#email", "admin-enrolled@example.invalid");
    await page.fill("#password", "correct-password");
    await page.getByRole("button", { name: "Sign in" }).click();
  }
  await expect(page).toHaveURL(new RegExp(`/${portal}$`));
}

test("the Home report shortcut uses Reports and sidebar collapse survives navigation", async ({ page }) => {
  await signIn(page, "staff");
  const rail = page.getByRole("complementary", { name: "Staff navigation" });
  await rail.getByRole("button", { name: "Collapse sidebar" }).click();
  await expect(rail.getByRole("button", { name: "Expand sidebar" })).toHaveAttribute("aria-expanded", "false");
  await expect(rail.getByText("Classes", { exact: true })).toBeHidden();
  await page.getByRole("link", { name: "Attendance reports", exact: true }).click();
  await expect(page).toHaveURL(/\/staff\/reports$/);
  await expect(rail.getByRole("link", { name: "Reports", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(rail.getByRole("button", { name: "Expand sidebar" })).toBeVisible();
  await rail.getByRole("button", { name: "Expand sidebar" }).click();
  await expect(rail.getByText("Classes", { exact: true })).toBeVisible();
  await page.goto("/staff/dashboard");
  await expect(page).toHaveURL(/\/staff\/reports$/);
});

test("the staff directory opens the selected profile while monthly attendance is still loading", async ({ page }) => {
  await signIn(page, "staff");
  const created = await page.request.post(`${mockApiBase()}/api/v1/students`, {
    data: { studentNumber: "PROFILE-77", fullName: "Monthly Profile Student", email: "profile77@example.invalid" },
    headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" },
  });
  expect(created.ok()).toBeTruthy();
  await page.getByRole("link", { name: "Students", exact: true }).click();
  await page.getByRole("searchbox", { name: "Search students" }).fill("PROFILE-77");
  const setOverview = (mode: "hold" | "release") => page.request.put(`${mockApiBase()}/__test__/student-overview-response`, {
    data: { mode }, headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" },
  });
  expect((await setOverview("hold")).status()).toBe(204);
  try {
    await page.getByRole("link", { name: /Monthly Profile Student/ }).click();
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Monthly Profile Student");
    await expect(page.getByLabel("Attendance month")).toBeVisible();
    await expect(page.getByRole("status", { name: "Loading student profile attendance" })).toBeVisible();
  } finally {
    await setOverview("release");
  }
  await expect(page.getByRole("region", { name: "Enrolled class types" })).toBeVisible();
  await page.getByRole("link", { name: "Previous month" }).click();
  await expect(page).toHaveURL(/\/attendance\?month=\d{4}-\d{2}$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Monthly Profile Student");
});

test("a verified student lands on their own profile and can filter the attendance history", async ({ page }) => {
  await signIn(page, "student");
  const month = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date(Date.now() - 8 * 86_400_000)).slice(0, 7);
  if (new URL(page.url()).searchParams.get("month") !== month) await page.goto(`/student?month=${month}`);
  await expect(page.getByText("Average attendance", { exact: true })).toBeVisible();
  const enrolled = page.getByRole("region", { name: "Enrolled class types" });
  await expect(enrolled.getByText("Waves and oscillations", { exact: true })).toHaveCount(1);
  await expect(enrolled).not.toContainText("Monday evening");
  const chart = page.getByRole("region", { name: "Attendance by class type" });
  await chart.getByRole("button", { name: /100% Waves and oscillations/ }).click();
  const history = page.getByRole("region", { name: "Class history" });
  await expect(history).toContainText("1 h 10 min connected");
  await expect(history).toContainText("Joined · pending");
  await history.getByText("3 connections", { exact: true }).click();
  await expect(history.getByText(/25 min/).first()).toBeVisible();
  await history.getByRole("button", { name: "Show all classes" }).click();
  await expect(chart.getByRole("button", { name: /Waves and oscillations/ })).toHaveAttribute("aria-pressed", "false");
  const futureMonth = new Date(Date.now() + 40 * 86_400_000).toISOString().slice(0, 7);
  await page.goto(`/student?month=${futureMonth}`);
  await expect(enrolled).toContainText("Far future class");
  await expect(chart).toContainText("No completed sessions this month.");
  await page.goto("/staff/students/00000000-0000-4000-8000-0000000000b2/attendance");
  await expect(page).toHaveURL(/\/student$/);
});
