import { test, expect, type Page } from "@playwright/test";
import { mockApiBase } from "./support/classes";

async function signInStaff(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/sign-in");
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

/** The mock API, as the signed-in staff member (the mock's fixed token). */
async function api(page: Page, method: "GET" | "POST" | "PUT", path: string, data?: unknown) {
  const response = await page.request.fetch(mockApiBase() + path, {
    method, data, headers: { Authorization: "Bearer fake-access-token.for-bff.e2e-test", "Content-Type": "application/json" },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}

const colomboDate = (ms: number) => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date(ms));
type CompletedClass = { id: string; title: string; endsAt: string; timeZone: string; expected: number; joined: number };

test.describe("Staff home", () => {
  test("Staff see the day's classes, move between days, and see the month calendar and live numbers", async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
    const p = test.info().project.name.toUpperCase();
    await signInStaff(page, "admin-enrolled@example.invalid");
    await expect(page.getByRole("link", { name: "Home", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "Staff dashboard", level: 1 })).toBeVisible();
    await expect(page.getByRole("region", { name: /^Classes on Today/ })).toBeVisible();
    await expect(page.getByRole("region", { name: "Needs attention" })).toHaveCount(0);

    // A long-titled class later today and one tomorrow.
    const course = await api(page, "POST", "/api/v1/courses", { code: `HOME-${p}`, title: "Home page course" });
    const batch = await api(page, "POST", "/api/v1/batches", { courseId: course.id, code: "A", name: "Batch A", startsOn: "2026-01-01" });
    const shortTitle = `Model Paper ${p} | 2027 Batch`;
    const long = `${shortTitle} | by Eng. Mithukshan Sivakumar | Professional Physics Educator`;
    const later = Date.now() + 2 * 60_000;
    for (const [title, start] of [[long, later], [`Theory ${p}`, later + 24 * 3600_000]] as const) {
      await api(page, "POST", "/api/v1/class-sessions", { title, startsAt: new Date(start).toISOString(),
        endsAt: new Date(start + 3600_000).toISOString(), timeZone: "Asia/Colombo", batchIds: [batch.id], zoomWebinarId: null });
    }

    // The automation spec can leave a live mock run behind when the entire suite shares a server.
    const monitors = await api(page, "GET", "/api/v1/zoom/monitors") as { live: { classSessionId: string } | null }[];
    const liveSession = monitors.find(m => m.live)?.live;
    const livePath = `/api/v1/class-sessions/${liveSession?.classSessionId}`;
    const cancelled = liveSession && await api(page, "PUT", livePath, {
      ...(await api(page, "GET", livePath)), status: "CANCELLED", batchIds: [batch.id],
    });
    try {
      await page.reload();
      const [latest] = await api(page, "GET", "/api/v1/attendance/recent-completed") as CompletedClass[];
      const recents = page.getByRole("region", { name: "Last completed class" });
      await expect(page.getByRole("region", { name: "Live now" })).toHaveCount(0);
      await expect(recents.getByRole("link", { name: latest.title, exact: true })).toHaveAttribute("href", `/staff/sessions/${latest.id}`);
      await expect(recents.getByRole("term")).toHaveText(["Expected", "Joined", "Attendance"]);
      await expect(recents.getByRole("definition").nth(0)).toHaveText(String(latest.expected));
      await expect(recents.getByRole("definition").nth(1)).toHaveText(String(latest.joined));
      const percent = latest.expected ? `${Math.round(latest.joined * 100 / latest.expected)}%` : "—";
      await expect(recents.getByRole("definition").nth(2)).toHaveText(percent);
      const nextCard = page.getByRole("region", { name: long, exact: true });
      await expect(nextCard.getByText("Next class", { exact: true })).toBeVisible();
      const nextLink = nextCard.getByRole("link", { name: long, exact: true });
      await expect(nextLink.locator(".one-line-text")).toHaveText(shortTitle);
      const nextBox = (await nextCard.boundingBox())!;
      const lastBox = (await recents.boundingBox())!;
      expect(Math.abs(nextBox.width - lastBox.width)).toBeLessThanOrEqual(1);
      if (test.info().project.name === "mobile") {
        expect(Math.abs(nextBox.x - lastBox.x)).toBeLessThanOrEqual(1);
        expect(nextBox.y + nextBox.height).toBeLessThan(lastBox.y);
      } else {
        expect(Math.abs(nextBox.y - lastBox.y)).toBeLessThanOrEqual(1);
        expect(nextBox.x + nextBox.width).toBeLessThan(lastBox.x);
      }
      for (const metric of await recents.getByRole("term").or(recents.getByRole("definition")).all()) {
        const metricBox = (await metric.boundingBox())!;
        expect(metricBox.x).toBeGreaterThanOrEqual(lastBox.x);
        expect(metricBox.y).toBeGreaterThanOrEqual(lastBox.y);
        expect(metricBox.x + metricBox.width).toBeLessThanOrEqual(lastBox.x + lastBox.width);
        expect(metricBox.y + metricBox.height).toBeLessThanOrEqual(lastBox.y + lastBox.height);
      }
      const daySchedule = page.getByRole("region", { name: /^Classes on Today/ });
      const attendance = page.getByRole("region", { name: "Attendance for the last 5 classes" });
      const monthCalendar = page.getByRole("region", { name: /^[A-Z][a-z]+ \d{4}$/ });
      await expect(attendance.getByRole("listitem").first()).toBeVisible();
      const [dayPanel, attendancePanel, calendarPanel] = (await Promise.all([
        daySchedule.boundingBox(), attendance.boundingBox(), monthCalendar.boundingBox(),
      ])).map(rect => rect!);
      expect(Math.abs(dayPanel.x - attendancePanel.x)).toBeLessThanOrEqual(1);
      expect(dayPanel.y + dayPanel.height).toBeLessThan(attendancePanel.y);
      if (test.info().project.name === "mobile") {
        expect(Math.abs(dayPanel.x - calendarPanel.x)).toBeLessThanOrEqual(1);
        expect(attendancePanel.y + attendancePanel.height).toBeLessThan(calendarPanel.y);
      } else {
        expect(dayPanel.x + dayPanel.width).toBeLessThan(calendarPanel.x);
        expect(Math.abs(dayPanel.y - calendarPanel.y)).toBeLessThanOrEqual(1);
        expect(Math.abs(attendancePanel.y + attendancePanel.height - (calendarPanel.y + calendarPanel.height))).toBeLessThanOrEqual(1);
      }
      if (test.info().project.name === "chromium") {
        const tooltip = nextCard.locator(".one-line-full");
        expect(await nextLink.locator(".one-line-text").evaluate(el => el.scrollWidth)).toBeLessThanOrEqual(
          await nextLink.locator(".one-line-text").evaluate(el => el.clientWidth),
        );
        await nextLink.focus();
        await page.keyboard.press("Tab");
        await page.keyboard.press("Shift+Tab");
        await expect(nextLink).toBeFocused();
        await expect(tooltip).toBeVisible();
        await expect(tooltip).toHaveText(long);
        await page.keyboard.press("Escape");
        await expect(tooltip).toBeHidden();
        await page.keyboard.press("Tab");

        await nextLink.hover();
        await expect(tooltip).toBeVisible();
        await expect(tooltip).toHaveText(long);
        expect(await tooltip.evaluate(el => el.matches(":popover-open"))).toBe(true);
        const tooltipBox = (await tooltip.boundingBox())!;
        expect(tooltipBox.x).toBeGreaterThanOrEqual(0);
        expect(tooltipBox.x + tooltipBox.width).toBeLessThanOrEqual(page.viewportSize()!.width);
        expect(await tooltip.evaluate(el => {
          const rect = el.getBoundingClientRect();
          return el.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
        })).toBe(true);
        await page.keyboard.press("Escape");
        await expect(tooltip).toBeHidden();
      }
    } finally {
      if (cancelled) await api(page, "PUT", livePath, { ...cancelled, status: "SCHEDULED" });
    }

    await page.reload();
    const day = page.getByRole("region", { name: /^Classes on Today/ });
    const row = day.getByRole("link", { name: new RegExp(long.replace(/[|]/g, "\\|")) });
    await expect(row).toBeVisible();
    await expect(row.locator(".one-line-text")).toHaveText(shortTitle);
    // A long title never widens the page, on any screen.
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
    // One line; on a pointer device the full title shows on hover, and Escape hides it again.
    const box = await row.locator(".one-line-text").boundingBox();
    expect(box!.height).toBeLessThan(30);
    if (test.info().project.name === "chromium") {
      await row.hover();
      await expect(row.locator(".one-line-full")).toBeVisible();
      await expect(row.locator(".one-line-full")).toHaveText(long);
      await page.keyboard.press("Escape");
      await expect(row.locator(".one-line-full")).toBeHidden();
    }

    // The top card shows what is live, or the next class when nothing is (another test may have started a live webinar).
    await expect(page.getByRole("region", { name: "Live now" }).or(page.getByText(/^(Next class|No class is live right now)$/))).toBeVisible();

    // Next and previous day.
    await day.getByRole("link", { name: "Next day" }).click();
    await expect(page).toHaveURL(new RegExp(`day=${colomboDate(later + 24 * 3600_000)}`));
    const tomorrow = page.getByRole("region", { name: /^Classes on Tomorrow/ });
    await expect(tomorrow.getByRole("link", { name: new RegExp(`Theory ${p}`) })).toBeVisible();
    await tomorrow.getByRole("link", { name: "Back to today" }).click();
    await expect(page).toHaveURL(/\/staff$/);

    // The month calendar marks today and its classes; choosing a day shows it in the list.
    const calendar = page.getByRole("region", { name: /^[A-Z][a-z]+ \d{4}$/ });
    const todayLink = calendar.getByRole("link", { name: /, today: \d+ class/ });
    const clock = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
    expect((await todayLink.getAttribute("title"))?.split("\n")).toContain(`${clock.format(later)}–${clock.format(later + 3600_000)} · ${long}`);
    const tomorrowStart = later + 24 * 3600_000;
    const tomorrowLink = calendar.getByRole("link", { name: new RegExp(`^${new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", weekday: "long", day: "numeric", month: "long", year: "numeric" })
      .format(new Date(tomorrowStart)).replace(",", "")}: \\d+ class`) });
    expect((await tomorrowLink.getAttribute("title"))?.split("\n")).toContain(`${clock.format(tomorrowStart)}–${clock.format(tomorrowStart + 3600_000)} · Theory ${p}`);
    const cell = tomorrowLink.locator("..");
    const compactTimes = cell.locator(".cal-compact-times");
    const tomorrowTime = compactTimes.getByText(clock.format(tomorrowStart), { exact: true });
    await expect(tomorrowTime).toBeVisible();
    await expect(tomorrowTime).toHaveAttribute("datetime", new Date(tomorrowStart).toISOString());
    if (test.info().project.name === "chromium") {
      const viewport = page.viewportSize()!;
      await page.setViewportSize({ width: 900, height: viewport.height });
      await expect(compactTimes).toBeHidden();
      await expect(cell.getByRole("link", { name: `${clock.format(tomorrowStart)} Theory ${p}` })).toBeVisible();
      await page.setViewportSize(viewport);
    }
    await tomorrowLink.click();
    await expect(page).toHaveURL(/#day-schedule$/);
    await expect(page.getByRole("region", { name: /^Classes on Tomorrow/ })).toBeVisible();
    await calendar.getByRole("link", { name: "Next month" }).click();
    await expect(page).toHaveURL(/month=\d{4}-\d{2}/);
    await page.getByRole("link", { name: "Back to this month" }).click();

    // A webinar Zoom reports live: its numbers replace the next-class card.
    await api(page, "POST", "/api/v1/zoom/monitors", { webinar: "84400000001" });
    await page.goto("/staff");
    const live = page.getByRole("region", { name: "Live now" });
    const card = live.getByRole("article", { name: "Live: A/L Physics 2027" });
    await expect(card).toBeVisible();
    await expect(card.getByText("Students online", { exact: true }).locator("..").getByRole("definition")).toHaveText("12");
    await expect(page.getByText("Next class", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Last completed class" })).toHaveCount(0);
    await expect(live.getByRole("button", { name: "Pause" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
    await page.getByRole("link", { name: "Attendance reports" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard$/);
  });

  test("the live online chart shows recorded samples on arrival and reload, and refresh can pause", async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await signInStaff(page, "admin-enrolled@example.invalid");
    const course = await api(page, "POST", "/api/v1/courses", { code: "CHART-TIME", title: "Chart time window" });
    const batch = await api(page, "POST", "/api/v1/batches", { courseId: course.id, code: "CHART", name: "Chart time batch", startsOn: "2026-01-01" });
    const now = Date.now();
    const startsAt = new Date(now - 10 * 60_000).toISOString();
    const endsAt = new Date(now + 50 * 60_000).toISOString();
    await api(page, "POST", "/api/v1/class-sessions", { title: "Scheduled live class", startsAt, endsAt, timeZone: "Asia/Colombo", batchIds: [batch.id] });
    const { live: run } = await api(page, "POST", "/api/v1/zoom/monitors", { webinar: "84400000001" }) as {
      live: { classSessionId: string; connectedNow: number; actualStartedAt: string };
    };
    const onlinePath = `/__test__/zoom-online/${run.classSessionId}`;
    await page.clock.install();
    await page.goto("/staff");
    const live = page.getByRole("region", { name: "Live now" });
    const article = live.getByRole("article", { name: "Live: A/L Physics 2027" });
    const figure = article.getByRole("figure", { name: "Students online over time" });
    const chart = figure.getByRole("img");
    const recorded = /3 observations · 4 online at .* · 12 online at /;
    await expect(chart).toHaveAccessibleName(recorded);
    await expect(chart.locator("text")).toHaveText(["14", "2"]);
    await expect(chart.locator("circle")).toHaveCount(3);
    const newestSegment = chart.locator(".live-online-segment");
    await expect(newestSegment).toHaveCount(0);
    const zoomBounds = figure.locator("time");
    await expect(zoomBounds.first()).toHaveAttribute("datetime", run.actualStartedAt);
    await expect(zoomBounds.first().locator("..")).toHaveText(/^Start /);
    await expect(zoomBounds.last().locator("..")).toHaveText(/^Now /);
    const sampledAt = (await zoomBounds.last().getAttribute("datetime"))!;
    const latestObservedAt = Date.parse(sampledAt);
    expect(latestObservedAt).toBeGreaterThan(Date.parse(run.actualStartedAt));
    expect(latestObservedAt).toBeLessThanOrEqual(Date.now());

    const scheduledChart = live.getByRole("article", { name: "In progress: Scheduled live class" })
      .getByRole("figure", { name: "Students online over time" });
    await expect(scheduledChart).toContainText("No online counts available yet.");
    await expect(scheduledChart.getByRole("img")).toHaveCount(0);
    expect(latestObservedAt).toBeLessThan(Date.parse(endsAt));
    const articleBox = (await article.boundingBox())!;
    const innerWidth = await live.evaluate(el => {
      const style = getComputedStyle(el);
      return el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    });
    expect(Math.abs(articleBox.width - innerWidth)).toBeLessThanOrEqual(1);
    if (test.info().project.name === "chromium") {
      const chartBox = (await chart.boundingBox())!;
      expect(Math.abs(chartBox.x + chartBox.width - (articleBox.x + articleBox.width))).toBeLessThanOrEqual(1);
    }

    try {
      const pause = live.getByRole("button", { name: "Pause" });
      const resume = live.getByRole("button", { name: "Resume" });
      await pause.click();
      await expect(resume).toBeVisible();
      await api(page, "PUT", onlinePath, { connectedNow: 17 });
      await page.clock.fastForward(31_000);
      await expect(chart).toHaveAccessibleName(recorded);
      await resume.click();
      await expect(pause).toBeVisible();
      await page.clock.fastForward(31_000);
      const refreshed = /4 observations · 4 online at .* · 17 online at /;
      await expect(chart).toHaveAccessibleName(refreshed);
      expect(Date.parse((await zoomBounds.last().getAttribute("datetime"))!)).toBeGreaterThan(latestObservedAt);
      await expect(chart.locator("text")).toHaveText(["19", "2"]);
      await expect(chart.locator("circle")).toHaveCount(4);
      await expect(newestSegment).toHaveAttribute("pathLength", "1");
      expect(await newestSegment.evaluate(el => getComputedStyle(el).animationName)).not.toBe("none");
      await page.emulateMedia({ reducedMotion: "reduce" });
      await expect(newestSegment).toHaveCSS("animation-name", "none");
      await page.reload();
      await expect(chart).toHaveAccessibleName(refreshed);
      await expect(chart.locator("circle")).toHaveCount(4);
      await expect(newestSegment).toHaveCount(0);
    } finally {
      await api(page, "PUT", onlinePath, { connectedNow: run.connectedNow });
    }
  });

  test("shows the five most recently completed classes independently of the calendar month", async ({ page }) => {
    await signInStaff(page, "admin-enrolled@example.invalid");
    const project = test.info().project.name.toUpperCase();
    const course = await api(page, "POST", "/api/v1/courses", { code: `RECENT-${project}`, title: "Recent classes course" });
    const batch = await api(page, "POST", "/api/v1/batches", { courseId: course.id, code: "RECENT", name: "Recent classes", startsOn: "2026-01-01" });
    const day = 24 * 3600_000;
    const endedNow = Date.now() - 60_000;
    const monthStart = Date.parse(`${colomboDate(endedNow).slice(0, 7)}-01T00:00:00+05:30`);
    const currentSummary = `Latest class ${project} – Model Papers for Physics | 2027 Batch & Repeaters`;
    const current = `${currentSummary} | Full class name stays readable`;
    const dates = [endedNow, ...[10, 11, 12, 13].map(offset => monthStart - offset * day)];
    for (const [index, end] of dates.entries()) {
      await api(page, "POST", "/api/v1/class-sessions", { title: index === 0 ? current : `Past class ${project} ${index}`,
        startsAt: new Date(end - 3600_000).toISOString(), endsAt: new Date(end).toISOString(),
        timeZone: "Asia/Colombo", batchIds: [batch.id], zoomWebinarId: null });
    }
    const completed = await api(page, "GET", "/api/v1/attendance/recent-completed") as CompletedClass[];
    expect(completed).toHaveLength(5);
    expect(completed[0].title).toBe(current);

    // Open a calendar month that cannot contain these completed classes.
    const futureMonth = colomboDate(monthStart + 45 * day).slice(0, 7);
    await page.goto(`/staff?month=${futureMonth}`);
    const chart = page.getByRole("region", { name: "Attendance for the last 5 classes" });
    const rows = chart.getByRole("listitem");
    await expect(rows).toHaveCount(5);
    for (const [index, item] of completed.entries()) {
      const row = rows.nth(index);
      await expect(row.getByRole("link", { name: item.title, exact: true })).toHaveAttribute("href", `/staff/sessions/${item.id}`);
      const date = new Intl.DateTimeFormat("en-GB", { timeZone: item.timeZone, weekday: "short", day: "numeric", month: "short" })
        .format(new Date(item.endsAt)).replace(",", "").replace(/Sept\b/, "Sep");
      await expect(row).toContainText(date);
    }
    await expect(rows.first()).toContainText("No students expected");
    await expect(rows.first().locator(".one-line-text")).toHaveText(currentSummary);
    await expect(rows.first().getByText("—", { exact: true })).toBeVisible();
    await expect(rows.first().getByRole("meter")).toHaveCount(0);
    const electricFields = rows.filter({ has: page.getByRole("link", { name: "Electric fields", exact: true }) });
    await expect(electricFields.getByText("67%", { exact: true })).toBeVisible();
    await expect(electricFields).toContainText("2 of 3 joined");
    await expect(electricFields.getByRole("meter", { name: "Electric fields attendance" })).toHaveAttribute("value", "67");
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width);
  });
});
