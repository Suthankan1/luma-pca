// UX audit: seeds the mock backend (tests/mock-auth-runner.mjs on LUMA_AUDIT_PORT, default 3095), then captures every
// staff and student route at five viewport sizes and records layout problems: horizontal overflow, touch targets
// smaller than 24 px, unlabeled controls. Usage: node scripts/ux-audit.mjs <outDir>
import { chromium } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";

const port = Number(process.env.LUMA_AUDIT_PORT ?? 3095);
const base = `http://127.0.0.1:${port}`;
const api = `http://127.0.0.1:${port + 1000}`;
const out = process.argv[2] ?? "ux-audit";
mkdirSync(out, { recursive: true });
const token = "fake-access-token.for-bff.e2e-test";
const viewports = [["desktop", 1440, 900], ["laptop", 1280, 800], ["tablet", 768, 1024], ["mobile", 390, 844], ["small", 320, 700]];

async function call(method, path, body) {
  const r = await fetch(api + path, { method, headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}

const browser = await chromium.launch(process.platform === "darwin" ? { channel: "chrome" } : {});
async function signIn(email, mfa) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`${base}/sign-in?portal=${mfa ? "staff" : "student"}`);
  await page.fill("#email", email);
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  if (mfa) { await page.waitForURL(/mfa/); await page.fill("#code", "rec-recovery"); await page.getByRole("button", { name: "Verify" }).click(); }
  await page.waitForURL(mfa ? /\/staff$/ : /\/student$/);
  return { context, page };
}

const staff = await signIn("admin-enrolled@example.invalid", true);
// Seed: a course with a batch, two students enrolled, a class held last week, two monitored webinars (one live).
const course = await call("POST", "/api/v1/courses", { code: "PHY-27", title: "A/L Physics 2027" });
const batch = await call("POST", "/api/v1/batches", { courseId: course.id, code: "MON", name: "Monday evening", startsOn: "2026-01-05" });
const students = [];
for (const [n, name] of [["PCA101", "Amaya Perera"], ["PCA102", "Kavin Silva"], ["PCA103", "Nila Fernando"]]) {
  const s = await call("POST", "/api/v1/students", { studentNumber: n, fullName: name, email: `${n.toLowerCase()}@example.invalid` });
  students.push(s);
  await call("POST", `/api/v1/batches/${batch.id}/enrollments`, { studentId: s.id, enrolledOn: "2026-01-05" });
}
const past = new Date(Date.now() - 6 * 86400_000); past.setUTCHours(12, 30, 0, 0);
const heldClass = await call("POST", "/api/v1/class-sessions", { title: "Waves and oscillations", startsAt: past.toISOString(),
  endsAt: new Date(past.getTime() + 2 * 3600_000).toISOString(), timeZone: "Asia/Colombo", batchIds: [batch.id], zoomWebinarId: "81234567890" });
const live = await call("POST", "/api/v1/zoom/monitors", { webinar: "84400000001" });
await call("POST", "/api/v1/zoom/monitors", { webinar: "84400000002" });
const runClass = live.live?.classSessionId ?? heldClass.id;
const team = await call("GET", "/api/v1/staff-accounts");
const staffId = Array.isArray(team) ? team[0]?.id : team?.items?.[0]?.id;

const staffRoutes = [
  "/staff", "/staff/dashboard", "/staff/dashboard?view=classes", "/staff/dashboard?view=grid", "/staff/webinars", "/staff/sessions",
  `/staff/sessions/${runClass}`, `/staff/sessions/${heldClass.id}`, "/staff/attendance", "/staff/zoom", "/staff/zoom/check",
  "/staff/academy", "/staff/students", `/staff/students/${students[0].id}`, "/staff/students/00000000-0000-4000-8000-0000000000b1/attendance",
  "/staff/students/import", "/staff/team", staffId ? `/staff/team/${staffId}` : null, "/staff/courses", `/staff/batches/${batch.id}`,
  "/staff/activity", "/account/password",
].filter(Boolean);
const studentRoutes = ["/student", "/student/attendance", "/student/classes/00000000-0000-4000-8000-0000000000f4", "/account/password"];
const publicRoutes = ["/", "/sign-in", "/forgot-password", "/activate", "/reset-password"];

const findings = [];
async function audit(page, routes, who) {
  for (const route of routes) {
    for (const [name, width, height] of viewports) {
      await page.setViewportSize({ width, height });
      const response = await page.goto(base + route, { waitUntil: "networkidle" });
      const report = await page.evaluate(() => {
        const vw = document.documentElement.clientWidth;
        const overflow = document.documentElement.scrollWidth - vw;
        const wide = [...document.querySelectorAll("body *")].filter(el => {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.right <= vw + 1) return false;
          // inside a deliberate horizontal scroller is fine
          for (let p = el.parentElement; p; p = p.parentElement) { const s = getComputedStyle(p); if (s.overflowX === "auto" || s.overflowX === "scroll") return false; }
          return true;
        }).slice(0, 5).map(el => `${el.tagName.toLowerCase()}.${String(el.className).split(" ")[0]}`);
        const small = [...document.querySelectorAll("a, button, input, select, summary")].filter(el => {
          const r = el.getBoundingClientRect(); return r.width > 0 && (r.height < 24 || r.width < 24) && getComputedStyle(el).visibility !== "hidden";
        }).length;
        const unlabeled = [...document.querySelectorAll("input:not([type=hidden]), select, textarea")]
          .filter(el => !el.labels?.length && !el.getAttribute("aria-label") && !el.getAttribute("aria-labelledby")).length;
        return { overflow, wide, small, unlabeled, height: document.documentElement.scrollHeight, h1: document.querySelector("h1")?.textContent };
      });
      const file = `${who}${route.replace(/[/?=&]/g, "_")}--${name}.jpg`;
      if (name === "desktop" || name === "mobile") await page.screenshot({ path: `${out}/${file}`, fullPage: true, type: "jpeg", quality: 55 });
      findings.push({ who, route, viewport: name, status: response?.status(), ...report });
    }
  }
}
await audit(staff.page, staffRoutes, "staff");
const student = await signIn("student@example.invalid", false);
await audit(student.page, studentRoutes, "student");
const anon = await browser.newContext();
await audit(await anon.newPage(), publicRoutes, "public");
writeFileSync(`${out}/findings.json`, JSON.stringify(findings, null, 1));
for (const f of findings.filter(f => f.overflow > 0 || f.status >= 400)) {
  console.log(`${f.who} ${f.route} @${f.viewport}: status ${f.status} overflow ${f.overflow}px ${f.wide.join(" ")}`);
}
const tall = findings.filter(f => f.viewport === "desktop").sort((a, b) => b.height - a.height).slice(0, 8);
console.log("Tallest desktop pages:", tall.map(f => `${f.route} ${f.height}px`).join(", "));
console.log("Small touch targets (mobile):", findings.filter(f => f.viewport === "mobile" && f.small > 0).map(f => `${f.route}:${f.small}`).join(" "));
console.log("Unlabeled controls:", findings.filter(f => f.viewport === "desktop" && f.unlabeled > 0).map(f => `${f.route}:${f.unlabeled}`).join(" "));
await browser.close();
