# LUMA frontend UX overhaul — November 2026

Branch `feat/luma-frontend-ux-overhaul`. This is a frontend-only change: every backend contract, route, role rule and business rule is unchanged.

## 1. How the audit was done

`frontend/scripts/ux-audit.mjs` seeds the mock backend with a realistic academy:

- course PHY-27, batch MON and three students;
- a finished class;
- one webinar that is live and has an ended run;
- one webinar with no fixed time.

It then opens every staff, student and public route as the right user at five viewports: 1440×900, 1280×800, 768×1024, 390×844 and 320×700. For each page it records:

- horizontal overflow (scrollers that are meant to scroll sideways are ignored);
- touch targets under 24 px;
- controls with no accessible name;
- page height.

It also saves screenshots. The before and after runs were both reviewed by eye.

```bash
LUMA_PLAYWRIGHT_PORT=3095 node tests/mock-auth-runner.mjs   # in frontend/, keep running
node scripts/ux-audit.mjs /path/to/out
```

A selection of the screenshots is kept in `docs/ux/screenshots/before|after/`. Each key screen has a `-desktop` (1440) and a `-mobile` (390) version. The data in them is the mock's made-up data; there are no real students.

## 2. Problems found

| Area | Problem | Severity | Solution |
|---|---|---|---|
| Navigation | "Dashboard" and "Attendance" were separate items with overlapping jobs. Analysts landed on a page they could mostly not use. | High | Attendance became one section with tabs: **To do · Reports · Batch totals · Rule & settings**. Nav groups are now **Overview / Teaching / Academy / Administration**. |
| Today (`/staff`) | The KPI tiles repeated the Reports page. What was live was not obvious. Work waiting did not stand out. | High | Today now shows, in order: **Live now** (live Zoom runs with connected counts) → **Needs attention** (highlighted when there is work) → **Today's classes** → **Recently finished** (36 h) → a one-line 7-day summary that links to Reports. |
| Attendance | One page mixed five jobs: finalising, appeals, batch totals, rule and engagement. It was 1255 px tall on desktop. | High | Each job got its own tab. To do opens by default, with a count badge. The rule is shown as one line that links to settings. Desktop is now 900 px. |
| Webinars | Every card showed every control at once: upcoming dates, sync, class picker, stop button and run history. Stopping a webinar moved its card away and lost the confirmation. | High | Each card shows its status first: Live / Not live / Not monitored, plus **Class matched / Class not set up / Class unresolved**. "Next in Zoom", "Manage webinar" and "Run history (n)" are disclosures. Stopped webinars stay in the list, sorted last. |
| Zoom review | Every decision, identity and replay form was open at once. The page was 5046 px on desktop and 6509 px on mobile. | High | Each form is behind a labelled disclosure ("Decide: link to a class or mark not a class", "Change decision", "Match to a student or mark not a student", "Replay this event"). The page is now 2683 px on desktop and 3832 px on mobile. |
| Student home | It repeated the full attendance list. There were two "My attendance" entry points. | Medium | The live or next class comes first, then a compact summary and a link: "View all attendance and appeals →". |
| Student attendance | Appeals lived on the home page, away from the records they refer to. | Medium | `/student/attendance` is now the one place for attendance. "Final attendance and appeals" lists each record with its own "Appeal this" disclosure. |
| Students, batch pages | The add-student, bulk-invite and ranking forms were always open. A batch page was 2001 px. | Medium | Each is a disclosure. The ranking opens on its own when a ranking query is in the URL. |
| Terminology | Academy, Zoom review and Zoom form texts still told staff to "schedule classes… import them from Zoom". | Medium | The texts now describe the real workflow: monitor the Zoom webinar by its ID under Webinars, and classes follow from Zoom's actual starts. |
| Mobile | Pages overflowed sideways at 320 px (webinars, classes, sign-in) and at 390 and 320 px (academy). There were many targets under 24 px. | Medium | Card grids use `minmax(min(…, 100%), 1fr)`. Inline links and sign-out are at least 28–32 px tall. The result is **no overflow at any viewport**. |

## 3. Navigation

| Group | Items |
|---|---|
| Overview | Today |
| Teaching | Webinars, Classes, Attendance (tabs: To do · Reports · Batch totals · Rule & settings) |
| Academy | Academy classes, Students |
| Administration | Staff, Courses & batches, Zoom review, Activity log |

`/staff/dashboard` still exists and now opens as **Attendance › Reports**, so existing links and bookmarks keep working. Analysts see Reports and Batch totals, with Batch totals as their default; they do not see To do. Student navigation is unchanged: My classes and My attendance.

## 4. Components

- **`components/Disclosure.tsx`**: a native `<details>/<summary>`, rendered on the server, with a summary label, an optional hint (such as a count) and a chevron. It works with the keyboard and screen readers and needs no JavaScript.
- **`components/staff/AttendanceTabs.tsx`**: an `<nav aria-label="Attendance sections">` whose links mark the current one with `aria-current="page"`. The To do tab carries a count badge.
- **`Kpis compact`**: a smaller summary row for the student home.
- **CSS (globals.css)**: covers the disclosure, section tabs, count badge, warning state, live panel highlight, "has work" row, rule line, the ≤420 px block, and the touch-target and overflow rules.
- **`tests/e2e/support/ui.ts` → `openSection(scope, label)`**: opens a collapsed section in tests, and does nothing if the section is already open or there is none.

## 5. Accessibility (WCAG 2.2 AA)

- Disclosures are native, so expanded/collapsed state is announced and Enter/Space toggle them.
- Section tabs are links in a labelled `nav`, with `aria-current`.
- Every region keeps its labelled heading. Tests address regions by name, which also checks this.
- Target size (2.5.8): almost every control is now at least 24 px. The few that remain are links inside sentences, which the criterion exempts.
- No horizontal scrolling of the page at 320 px (1.4.10 Reflow).
- The audit found no unlabeled controls. The axe spec (`@axe-core/playwright`) passes on desktop and mobile.

## 6. Before and after (from the audit)

The overflow column counts how many of the five viewports overflowed. Touch targets are summed over all five viewports.

| Route | Overflowing viewports | Targets < 24 px | Desktop height | Mobile height |
|---|---|---|---|---|
| /staff (Today) | 0 → 0 | 45 → 5 | 900 → 900 | 1459 → 1520 |
| /staff/attendance | 0 → 0 | 24 → 5 | 1255 → 900 | 1810 → 1087 |
| /staff/webinars | 1 → 0 | 24 → 4 | 1563 → 1497 | 2444 → 2139 |
| /staff/sessions | 1 → 0 | 30 → 0 | 900 → 900 | 1058 → 1090 |
| /staff/zoom | 0 → 0 | 44 → 30 | 5046 → 2683 | 6509 → 3832 |
| /staff/academy | 2 → 0 | 20 → 15 | 900 → 900 | 1440 → 1130 |
| /staff/students | 0 → 0 | 37 → 8 | 1220 → 900 | 1972 → 1301 |
| /staff/batches/{id} | 0 → 0 | 50 → 15 | 2001 → 1841 | 2834 → 2400 |
| /staff/team | 0 → 0 | 49 → 15 | 900 → 900 | 1333 → 1381 |
| /student | 0 → 0 | 35 → 0 | 1106 → 900 | 2033 → 1536 |
| /student/attendance | 0 → 0 | 15 → 0 | 900 → 1150 | 1807 → 2182 |
| /sign-in | 1 → 0 | 5 → 5 | 900 → 900 | 1093 → 1093 |

Student attendance grew because the appeals moved there from the home page.

## 7. Business rules preserved

- Webinars are monitored by Zoom webinar ID or link only. LUMA has no scheduling workflow, and Zoom's schedule is shown read-only.
- Actual Zoom starts drive monitoring. Occurrences stay separate, and rejoin intervals are unioned. None of this logic was touched.
- The Academy database is still read-only (`public.student`, `public.payment`), and its attendance table is never read.
- Student-ID sign-in is unchanged. No MFA screens or registration were added.
- No feature was removed. Each was moved into a tab or a disclosure.

## 8. Verification

- `npm run lint`, `npm run typecheck`: pass.
- `npm test`: 79 pass.
- `npm run build`: pass.
- `npm run test:smoke`: pass.
- `npx playwright test` (chromium and Pixel 7): **147 passed, 3 skipped by design**.
- Real stack, `scripts/staff-ui-real-stack.sh` (Docker Postgres, backend jar, Chrome): pass.
- Audit: no overflow at any of the 5 viewports, and no unlabeled controls.

## 9. Known limitations

- Zoom review still has about 30 small targets, summed over the viewports. They are mostly inline links inside occurrence rows; they are exempt, but could be made bigger.
- The Team and Courses & batches forms are still always open. They are used rarely, so they were left as they were.
- The class detail page is unchanged apart from the shared CSS.
- Loading states use the existing `loading.tsx` skeletons. Error states use the existing per-page alerts.
