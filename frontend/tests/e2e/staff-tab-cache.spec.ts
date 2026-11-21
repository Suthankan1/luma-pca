import { expect, test } from "@playwright/test";

test("the staff directory reopens from memory and fetches again after expiry", async ({ page }) => {
  await page.clock.install();
  await page.goto("/sign-in?portal=staff");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);

  const sidebar = page.getByRole("navigation", { name: "Staff sections" });
  const students = sidebar.getByRole("link", { name: "Students", exact: true });
  const classes = sidebar.getByRole("link", { name: "Classes", exact: true });
  const directory = page.getByRole("heading", { name: "Student directory" });
  const schedule = page.getByRole("region", { name: "Monthly schedule" });
  let directoryRequests = 0;
  page.on("request", request => {
    if (new URL(request.url()).pathname === "/staff/students" && request.headers().rsc === "1"
      && !request.headers()["next-router-prefetch"]) directoryRequests++;
  });

  await students.click();
  await expect(directory).toBeVisible();
  await classes.click();
  await expect(schedule).toBeVisible();
  const before = directoryRequests;

  // Cross the original 30-second window using the browser clock, without making the test wait.
  await page.clock.fastForward(45_000);
  await students.click();
  await expect(directory).toBeVisible();
  expect(directoryRequests).toBe(before);

  await classes.click();
  await expect(schedule).toBeVisible();
  await page.clock.fastForward(100_000);
  await students.click();
  await expect(directory).toBeVisible();
  expect(directoryRequests).toBeGreaterThan(before);

  await page.getByRole("link", { name: "Previous month", exact: true }).click();
  await expect(page).toHaveURL(/\/staff\/students\?month=\d{4}-\d{2}$/);
  await expect(directory).toBeVisible();
  const selectedUrl = page.url();
  await classes.click();
  await expect(schedule).toBeVisible();
  // Let the remembered month link finish any background prefetch before testing the click.
  await page.clock.fastForward(45_000);
  await page.waitForLoadState("networkidle");
  const beforeMonthReturn = directoryRequests;
  await students.click();
  await expect(page).toHaveURL(selectedUrl);
  await expect(directory).toBeVisible();
  expect(directoryRequests).toBe(beforeMonthReturn);
});
