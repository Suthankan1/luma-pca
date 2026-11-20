import { join } from "node:path";
import { pathToFileURL } from "node:url";

const origin = new URL(process.env.LUMA_LIGHTHOUSE_ORIGIN ?? "http://127.0.0.1:3090");
if (origin.protocol !== "http:" || origin.hostname !== "127.0.0.1" || origin.username || origin.password) {
  throw new Error("Run this check only against the loopback mock server");
}
const tools = process.env.LUMA_LIGHTHOUSE_TOOLS;
if (!tools) throw new Error("Set LUMA_LIGHTHOUSE_TOOLS to the isolated Lighthouse npm prefix");
const load = file => import(pathToFileURL(join(tools, "node_modules", file)).href);
const [{ default: lighthouse }, { launch }] = await Promise.all([
  load("lighthouse/core/index.js"), load("chrome-launcher/dist/index.js"),
]);

const login = await fetch(new URL("/api/session/login", origin), {
  method: "POST", redirect: "manual", headers: { Origin: origin.origin },
  body: new URLSearchParams({ email: "admin-enrolled@example.invalid", password: "correct-password", portal: "staff" }),
});
const cookie = login.headers.getSetCookie().map(value => value.split(";", 1)[0]).join("; ");
if (login.status !== 303 || !cookie || !login.headers.get("location")?.endsWith("/staff")) {
  throw new Error("The synthetic staff sign-in failed");
}

const chrome = await launch({ chromeFlags: ["--headless", "--no-sandbox", "--disable-dev-shm-usage"] });
const budgets = { "largest-contentful-paint": 1500, "cumulative-layout-shift": 0.05, "total-blocking-time": 300 };
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
try {
  for (const route of ["/staff/sessions", "/staff/class-types", "/staff/students"]) {
    const runs = [];
    for (let i = 0; i < 3; i++) {
      const result = await lighthouse(new URL(route, origin).href, { port: chrome.port, logLevel: "error",
        onlyCategories: ["performance"], extraHeaders: { Cookie: cookie } }, { extends: "lighthouse:default",
        settings: { formFactor: "desktop", screenEmulation: { mobile: false, width: 1366, height: 768, deviceScaleFactor: 1 },
          throttlingMethod: "provided" } });
      const lhr = result.lhr;
      if (lhr.runtimeError || new URL(lhr.finalDisplayedUrl).pathname !== route) throw new Error(`Lighthouse did not reach ${route}`);
      runs.push(lhr.audits);
    }
    for (const [metric, budget] of Object.entries(budgets)) {
      const value = median(runs.map(audits => audits[metric].numericValue));
      console.log(`${route} ${metric} ${value.toFixed(3)} (budget ${budget})`);
      if (!Number.isFinite(value) || value > budget) process.exitCode = 1;
    }
  }
} finally {
  await chrome.kill();
}
