import { openSection } from "./support/ui";
import { scheduleClass } from "./support/classes";
import { test, expect, type Page } from "@playwright/test";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Less manual work for staff", () => {
  let p = "ad";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "am" : "ad") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("Classes shows Zoom's scheduled and held classes, and a Zoom link is tracked from the same screen", async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium", "One mock Zoom account: tracked once");
    await signInStaff(page, "admin-enrolled@example.invalid");
    await page.goto("/staff/webinars");
    await expect(page).toHaveURL(/\/staff\/sessions$/);
    await expect(page.getByRole("heading", { name: "Classes", level: 1 })).toBeVisible();
    await expect(page.getByRole("complementary", { name: "Staff navigation" }).getByRole("link", { name: /Webinars/ })).toHaveCount(0);
    // Only one field: the webinar ID or link. No title, date, time or recurrence to type.
    const track = page.getByRole("region", { name: "TRACK A ZOOM LINK" });
    await expect(track.getByRole("textbox")).toHaveCount(1);
    await expect(track.locator("input[type=date], input[type=time]")).toHaveCount(0);

    const link = track.getByLabel("Zoom link or webinar ID to track");
    await link.fill("https://zoom.us/webinar/register/WN_abc");
    await track.getByRole("button", { name: "Track", exact: true }).click();
    await expect(track.locator(".session-error")).toContainText("ID");
    await link.fill("844 9999 9999");
    await track.getByRole("button", { name: "Track", exact: true }).click();
    await expect(track.locator(".session-error")).toContainText("not in the academy’s Zoom account");
    await link.fill("https://us02web.zoom.us/j/84400000001?pwd=x");
    await track.getByRole("button", { name: "Track", exact: true }).click();
    await expect(track.locator(".form-ok")).toContainText("Tracking “A/L Physics 2027”");

    // Zoom started it about two hours before its scheduled time: it is a live class now, and Zoom's next dates are expected.
    await page.reload();
    const classes = page.getByRole("region", { name: "CLASSES" });
    const live = classes.getByRole("listitem", { name: "A/L Physics 2027, Live" });
    await expect(live).toContainText("Live attendance →");
    await expect(classes.getByRole("listitem", { name: "A/L Physics 2027, Expected" }).first()).toContainText("Scheduled in Zoom");
    for (const name of ["Save changes", "Schedule class", "Cancel class", "Create classes"]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }

    // The tracked links stay out of the way, with only what is needed per webinar.
    await openSection(track, "Tracked Zoom links");
    const tracked = track.getByRole("listitem", { name: "Tracked 844 0000 0001" });
    await expect(tracked).toContainText("A/L Physics 2027");
    await expect(tracked).toContainText("(Asia/Colombo)");

    // A webinar with no fixed time in Zoom: nothing is invented, and its Academy class is chosen here.
    await link.fill("84400000002");
    await track.getByRole("button", { name: "Track", exact: true }).click();
    await expect(track.locator(".add-webinar .form-ok")).toContainText("Tracking “Office hours”");
    await openSection(track, "Tracked Zoom links");
    const office = track.getByRole("listitem", { name: "Tracked 844 0000 0002" });
    await expect(office).toContainText("Recurring, no fixed time in Zoom");
    await expect(office).toContainText("Academy class not set");
    await office.getByLabel("Academy class").selectOption("Admission");
    await office.getByRole("button", { name: "Save" }).click();
    await expect(office.locator(".form-ok")).toHaveText("Academy class set to “Admission”.");
    await office.getByRole("button", { name: "Stop tracking Office hours" }).click();
    await expect(office.locator(".form-ok")).toContainText("The Zoom webinar is unchanged");
    await expect(office).toContainText("Stopped");

    // The live class shows Zoom's schedule beside the actual start.
    await live.getByRole("link").click();
    const run = page.getByRole("region", { name: "ZOOM RUN" });
    await expect(run).toContainText("Live");
    await expect(run).toContainText("Zoom’s report");
    await expect(page.locator(".sub").first()).toContainText("Live now");
    const timeline = page.getByRole("figure", { name: "Students online over time" });
    await expect(timeline).toContainText("12 online");
    await expect(timeline).toContainText("Now");
    const refresh = page.locator(".live-refresh");
    await expect(refresh).toContainText("Updating every 30 seconds");
    await refresh.getByRole("button", { name: "Pause" }).click();
    await expect(refresh.getByRole("button", { name: "Resume" })).toBeVisible();

    const endedDay = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit",
    }).format(new Date(Date.now() - 7 * 86_400_000));
    await page.goto(`/staff/sessions?from=${endedDay}`);
    await classes.getByRole("listitem", { name: "A/L Physics 2027, Ended" }).getByRole("link").click();
    await expect(page.locator(".session-metrics").getByText("1 h 50 min", { exact: true })).toBeVisible();
    await expect(timeline).toContainText("221 samples");
    await expect(timeline).toContainText("End");
    await expect(timeline.locator(".session-timeline-line")).toHaveAttribute("d", /^M[^C]+C/);
    const scrubber = timeline.getByRole("slider", { name: "Explore timeline" });
    await expect(scrubber).toHaveAttribute("aria-valuetext", /0 online$/);
    await scrubber.press("Home");
    await expect(scrubber).toHaveAttribute("aria-valuetext", /1 online$/);
    await scrubber.press("End");
    await expect(scrubber).toHaveAttribute("aria-valuetext", /0 online$/);
  });

  test("Ended classes wait in one list and are finalised together; the owner can switch on automatic finalisation", async ({ page }) => {
    await signInStaff(page, "owner-enrolled@example.invalid");
    await page.goto("/staff/attendance?section=settings");
    const rule = page.getByRole("region", { name: "ATTENDANCE RULE" });
    await page.locator("#policy-percent").fill("75");
    await page.locator("#policy-late").fill("10");
    await page.locator("#policy-description").fill("Approved at the staff meeting");
    await rule.getByLabel(/Finalise each class automatically/).check();
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");
    await page.reload();
    await expect(rule.getByLabel(/Finalise each class automatically/)).toBeChecked();
    await page.goto("/staff/attendance");
    await expect(page.getByRole("region", { name: "WAITING TO BE FINALISED" })).toContainText("Automatic finalisation is on");
    await page.goto("/staff/attendance?section=settings");
    await rule.getByLabel(/Finalise each class automatically/).uncheck();
    await page.getByRole("button", { name: "Save rule" }).click();
    await expect(page.locator("#policy-feedback")).toHaveText("Attendance rule saved.");

    // A class that ended yesterday, with a webinar ID, waits for finalisation.
    await signInStaff(page, "admin-enrolled@example.invalid");
    const yesterday = new Date(Date.now() - 86_400_000 + 5.5 * 3_600_000).toISOString().slice(0, 10);
    const C = `FIN-${p.toUpperCase()}`;
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`fin-${p}`);
    await page.locator("#course-title").fill("Finalise course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${C} · Finalise course` });
    await page.locator("#batch-code").fill("a");
    await page.locator("#batch-name").fill("Batch A");
    await page.locator("#batch-starts").fill("2026-01-01");
    await page.getByRole("button", { name: "Create batch" }).click();
    await page.goto("/staff/sessions");
    await scheduleClass(page, { title: `Ended class ${p}`, date: yesterday, start: "09:00", end: "10:00", batch: `${C} · A · Batch A`, webinar: "838 1234 5678" });

    await page.goto("/staff/attendance");
    const waiting = page.getByRole("region", { name: "WAITING TO BE FINALISED" });
    await expect(waiting.getByRole("listitem", { name: `Pending Ended class ${p}` })).toContainText("Ready to finalise");
    await waiting.getByRole("button", { name: /Finalise \d+ ready class/ }).click();
    await expect(page.locator("#finalise-all-feedback")).toContainText("Finalised");
    await page.reload();
    await expect(waiting.getByRole("listitem", { name: `Pending Ended class ${p}` })).toHaveCount(0);
  });
});
