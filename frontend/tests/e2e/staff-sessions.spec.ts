import { openSection } from "./support/ui";
import { test, expect, type Page } from "@playwright/test";
import { mockApiBase, scheduleClass } from "./support/classes";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

/** A course with batches A and B, and one student enrolled in A from 5 Jan 2027. */
async function setUp(page: Page, code: string) {
  await page.goto("/staff/courses");
  await page.locator("#course-code").fill(code);
  await page.locator("#course-title").fill("Class course");
  await page.getByRole("button", { name: "Create course" }).click();
  for (const batch of ["A", "B"]) {
    await page.locator("#batch-course").selectOption({ label: `${code.toUpperCase()} · Class course` });
    await page.locator("#batch-code").fill(batch);
    await page.locator("#batch-name").fill(`Batch ${batch}`);
    await page.locator("#batch-starts").fill("2027-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${batch} created.`);
  }
  await page.goto("/staff/students/manage");
  await openSection(page, "Add a student who is not in the Academy records");
  await page.locator("#student-number").fill(`${code}-001`);
  await page.locator("#student-name").fill("Class Student");
  await page.locator("#student-email").fill(`${code}.student@example.invalid`);
  await page.getByRole("button", { name: "Add student" }).click();
  await page.getByRole("link", { name: "Open the new student’s record →" }).click();
  await page.locator("#enroll-batch").selectOption({ label: `${code.toUpperCase()} · A · Batch A` });
  await page.locator("#enroll-date").fill("2027-01-05");
  await page.getByRole("button", { name: "Enrol", exact: true }).click();
  await expect(page.locator("#enroll-feedback")).toHaveText("Enrolled.");
}

test.describe("Classes (Zoom owns schedules)", () => {
  let p = "d";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "m" : "d") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("Classes streams the month control and class types while Zoom is still loading", async ({ page }) => {
    await signInAsAdmin(page);
    const setMonitors = (mode: string) => page.request.put(`${mockApiBase()}/__test__/zoom-monitor-response`, {
      data: { mode }, headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" },
    });
    expect((await setMonitors("hold")).status()).toBe(204);
    try {
      await page.goto("/staff/sessions", { waitUntil: "commit" });
      await expect(page.getByLabel("Class month", { exact: true })).toBeVisible();
      await expect(page.getByRole("region", { name: "CLASS TYPES", exact: true })).toBeVisible();
      await expect(page.getByText("Loading tracked links…")).toBeVisible();
      expect((await setMonitors("fail")).status()).toBe(204);
      await expect(page.getByRole("region", { name: "TRACK A ZOOM LINK" }).getByRole("alert")).toContainText("couldn’t load");
      expect((await setMonitors("normal")).status()).toBe(204);
      await page.getByRole("region", { name: "TRACK A ZOOM LINK" }).getByRole("button", { name: "Retry" }).click();
      await expect(page.getByRole("region", { name: "TRACK A ZOOM LINK" }).getByRole("alert")).toHaveCount(0);
    } finally {
      await setMonitors("normal");
    }
  });

  test("Classes are read-only: no scheduling, editing, moving or cancelling controls remain", async ({ page }) => {
    await signInAsAdmin(page);
    await setUp(page, `cl-${p}`);
    const P = `CL-${p.toUpperCase()}`;
    // A class from before the change (history), created directly in the API.
    const old = await scheduleClass(page, { title: `Waves ${p}`, date: "2027-01-06", start: "18:00", end: "20:00", batch: `${P} · A · Batch A` });

    await page.goto("/staff/sessions?from=2027-01-03");
    for (const name of ["Schedule class", "Create series", "Check the series", "Save changes", "Cancel class", "Create classes"]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }
    await expect(page.locator("#schedule-title, #series-title")).toHaveCount(0);
    // One screen: tracking a Zoom link sits above the classes, grouped by day.
    await expect(page.getByRole("region", { name: "TRACK A ZOOM LINK" })).toBeVisible();
    const day = page.getByRole("region", { name: "CLASSES" }).getByRole("group", { name: "Wednesday 6 January 2027" });
    const row = day.getByRole("listitem").filter({ hasText: `Waves ${p}` });
    await expect(row).toContainText("18:00–20:00");
    await expect(row.getByRole("link")).toHaveAttribute("href", `/staff/sessions/${old.id}?month=2027-01`);
    await expect(page.locator("#class-batch")).toHaveCount(0);

    // Its attendance roster includes expected students and the class stays read-only.
    await page.goto(`/staff/sessions/${old.id}`);
    await expect(page.getByRole("region", { name: "Student attendance results" })).toContainText(`cl-${p}-001`.toUpperCase());
    await expect(page.getByRole("region", { name: "EXPECTED STUDENTS" })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "ZOOM", exact: true })).toHaveCount(0);
    await expect(page.locator("#edit-title, #edit-date, #status-select")).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Save changes|Cancel class|Restore class/ })).toHaveCount(0);

    // The old import route now leads to the Classes screen, where Zoom links are tracked.
    await page.goto("/staff/zoom/import");
    await expect(page).toHaveURL(/\/staff\/sessions$/);
  });

  test("the schedule and class types share a month, with completed classes charted and today in view", async ({ page }) => {
    await signInAsAdmin(page);
    const code = `month-${p}`;
    await setUp(page, code);
    const batch = `${code.toUpperCase()} · A · Batch A`;
    const year = p.startsWith("m") ? "2020" : "2024";
    for (const [date, title] of [["02-01", "February opening"], ["02-29", "February review"], ["03-01", "March opening"]]) {
      await scheduleClass(page, { title, date: `${year}-${date}`, start: "18:00", end: "19:00", batch });
    }
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" })
      .format(new Date());
    await scheduleClass(page, { title: "Today's fixture", date: today, start: "20:00", end: "21:00", batch });
    if (!today.endsWith("-01")) {
      for (let i = 0; i < 10; i++) {
        await scheduleClass(page, { title: `Earlier fixture ${i}`, date: `${today.slice(0, 7)}-01`,
          start: `${String(8 + i).padStart(2, "0")}:00`, end: `${String(9 + i).padStart(2, "0")}:00`, batch });
      }
    }

    await page.goto("/staff/sessions");
    const schedule = page.getByRole("region", { name: "Monthly schedule" });
    const todayGroup = schedule.locator(`[data-schedule-day="${today}"]`);
    await expect(todayGroup.getByText("Today", { exact: true })).toBeVisible();
    await expect.poll(() => todayGroup.locator("h3").evaluate(heading => {
      const panel = heading.closest(".classes-panel-scroll")!.getBoundingClientRect();
      const bounds = heading.getBoundingClientRect();
      return bounds.top >= panel.top - 2 && bounds.bottom <= panel.bottom + 2;
    })).toBe(true);
    if (!today.endsWith("-01")) {
      await expect.poll(() => schedule.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
    }

    await page.getByLabel("Class month", { exact: true }).fill(`${year}-02`);
    await page.getByRole("button", { name: "View", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`month=${year}-02$`));
    const classes = page.getByRole("region", { name: "CLASSES", exact: true });
    const types = page.getByRole("region", { name: "CLASS TYPES", exact: true });
    await expect(classes).toContainText(`February ${year}`);
    await expect(types).toContainText(`February ${year}`);
    await expect(schedule.getByRole("listitem", { name: /February/ })).toHaveCount(2);
    await expect(schedule.getByRole("listitem", { name: /March opening/ })).toHaveCount(0);
    const chart = page.getByRole("list", { name: "ATTENDANCE BY CLASS", exact: true });
    await expect(chart.getByRole("listitem")).toHaveCount(2);
    await expect(chart.getByRole("listitem", { name: new RegExp(`February review.*29 Feb ${year}`) })).toBeVisible();
    const sessionHref = (await schedule.getByRole("listitem", { name: /February review/ }).getByRole("link").getAttribute("href"))!;
    expect(sessionHref).toContain(`?month=${year}-02`);
    await page.goto(sessionHref);
    await expect(page.getByRole("link", { name: "Back to Classes" })).toHaveAttribute("href", `/staff/sessions?month=${year}-02`);
    await page.getByRole("link", { name: "Back to Classes" }).click();
    await types.getByRole("link", { name: /February review/ }).click();
    await expect(page.getByRole("heading", { name: "February review", level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: /Back to Classes/ })).toHaveAttribute("href", `/staff/sessions?month=${year}-02`);

    const menuLink = page.getByRole("navigation", { name: "Staff sections" }).getByRole("link", { name: "Class Types", exact: true });
    await menuLink.click();
    await expect(page.getByRole("heading", { name: "Class Types", level: 1 })).toBeVisible();
    await expect(menuLink).toHaveAttribute("aria-current", "page");
    await expect(schedule).toHaveCount(0);
    await page.getByLabel("Class month", { exact: true }).fill(`${year}-02`);
    await page.getByRole("button", { name: "View", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/staff/class-types\\?month=${year}-02$`));
    const row = page.getByRole("region", { name: "Class types for the selected month" }).getByRole("listitem", { name: /February review/ });
    await expect(row.getByText("Zoom link not tracked")).toBeVisible();
    await expect(row.getByLabel("Academy payment class")).toBeEnabled();
    await expect(row.getByLabel("Zoom link or webinar ID")).toBeVisible();
    const holdRead = async (path?: string) => {
      const result = await page.request.put(`${mockApiBase()}/__test__/class-type-read`, {
        headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test" }, data: { path },
      });
      expect(result.status()).toBe(204);
    };
    await holdRead("/api/v1/academy/classes");
    try {
      await page.reload({ waitUntil: "commit" });
      await expect(row.getByText("Loading payment classes…")).toBeVisible();
      await expect(menuLink).toHaveAttribute("aria-current", "page");
    } finally {
      await holdRead();
    }
    await page.getByRole("region", { name: "Class types for the selected month" }).getByRole("link", { name: /February review/ }).click();
    await expect(page.getByRole("heading", { name: "February review", level: 1 })).toBeVisible();
    const paymentRoster = page.getByRole("region", { name: "Payment roster" });
    await holdRead("/api/v1/attendance/dashboard");
    try {
      await page.reload({ waitUntil: "commit" });
      await expect(page.getByText("Loading class details…")).toBeVisible();
      await expect(menuLink).toHaveAttribute("aria-current", "page");
    } finally {
      await holdRead();
    }
    await expect(paymentRoster).toBeVisible();
    const rosterBox = await paymentRoster.boundingBox();
    const metricsBox = await page.locator(".class-detail-metrics").boundingBox();
    expect(metricsBox!.y - (rosterBox!.y + rosterBox!.height)).toBeGreaterThanOrEqual(16);
    await expect(paymentRoster.getByLabel("Academy payment class")).toBeEnabled();
    await paymentRoster.getByLabel("Academy payment class").selectOption("Admission");
    await paymentRoster.getByLabel("Zoom link or webinar ID").fill("84400000002");
    await paymentRoster.getByRole("button", { name: "Track and sync" }).click();
    await expect(paymentRoster.getByText("Admission synced. 1 expected student per run.")).toBeVisible();
    await expect(menuLink).toHaveAttribute("aria-current", "page");
    const typeUrl = new URL(page.url());
    await page.goto(`${sessionHref.split("?")[0]}${typeUrl.search}`);
    await expect(menuLink).toHaveAttribute("aria-current", "page");
    await expect(menuLink).toHaveAttribute("href", `/staff/class-types?month=${year}-02`);
    await page.getByRole("link", { name: "Back to class type" }).click();
    await expect(page).toHaveURL(url => url.pathname === typeUrl.pathname &&
      [...typeUrl.searchParams].every(([key, value]) => url.searchParams.get(key) === value));
    await page.getByRole("link", { name: /Back to Class Types/ }).click();
    await expect(page).toHaveURL(new RegExp(`/staff/class-types\\?month=${year}-02$`));
  });
});
