# LUMA × Physics Cube Academy — Frontend Demo

A fully static, responsive, interactive frontend prototype of a proposed **Zoom Webinar Attendance & Engagement Management System** for Physics Cube Academy.

## Run locally

**Simplest:** Double-click `index.html` in a modern browser (Chrome, Edge, Firefox, Safari). No installation, Node.js, build step, internet, API keys, or backend is necessary.

**Optional local server:** From this directory run:

```bash
python -m http.server 8000
```

Open http://localhost:8000/ in your browser.

## Demonstrate the system

Use the **Staff / Student** switch at the top right. On mobile, use the menu icon for all routes and the bottom navigation for the most common screens.

### Staff experience

- **Overview:** simulated current class, academy KPIs, decisions desk, attendance trend.
- **Live webinar:** start/pause a local event stream and advance simulated events.
- **Attendance ledger:** choose a class, search learners, see connection duration from joined intervals and policy-based attendance status. Export class CSV.
- **Student records:** inspect an individual student's timeline and attendance history.
- **Engagement:** distinguish Q&A, poll, custom portal reactions and unverified native Zoom emojis.
- **Pulse studio:** review sample learning questions, move between questions, and preview as a student.
- **Monthly ranking:** switch between Top 10/Top 100 and sort by total index, portal reactions or Q&A. Export ranking.
- **Reports:** download five types of sample CSVs (plus pulse responses via Export All).
- **Review queue:** open ambiguous records, enter a reason, optionally select a matching student, and mark reviewed; changes appear in the overview.
- **Identity mapping:** browse fictitious learners and understand identity matching.
- **Zoom integration:** explains what a production integration would need; does not connect.
- **Rules & privacy:** change the attendance threshold (50–100%) and see statuses recalculate; reset all demo changes.

### Student experience

- **Home:** personal overview and participation data.
- **My attendance:** view connection intervals and class-by-class history; request a correction (simulated).
- **Learning pulse:** answer a question and submit it; stored locally and reflected in staff analytics.
- **My reactions:** use ❤️ 👍 👏 🙋 buttons in the academy portal, update sample reaction counts and rankings.
- **My profile:** transparency about how participation data is collected.

## Important accuracy limitations

- **This is frontend-only.** All student details, reports, connection intervals, join/leave events, Q&A, and rankings are fictional demonstration data. No requests are sent to Zoom or any server.
- **Native Zoom Webinar reactions cannot be promised as identifiable per-student metrics via the standard REST API.** The portal reaction buttons intentionally represent a *separate, academy-owned* input channel.
- **Presence is not attention.** Connected time does not prove watching, listening or understanding. The demo does not do eye tracking, webcam surveillance or monitor computer activity.
- A real version would require academy account approval, Zoom Webinar license/scopes, Server-to-Server OAuth, secure backend, webhook signature verification, identity mapping, reconciliation, authenticated student/staff roles, secure databases, opt-in policies and data protection.
- Browser `localStorage` remembers simulated choices; use **Staff → Rules & privacy → Reset demo data** to restore the original sample.

## Files

- `index.html` — page shell
- `styles.css` — full responsive blue/white design system
- `app.js` — demo dataset, route rendering, interactions and CSV exports
- `assets/physics-cube-academy-logo.jpg` — academy logo supplied for this prototype

## Design reference

Editable screens on Figma: https://www.figma.com/design/QVzsewJyikM94Xp07hDwwi

The application is adapted to the brand palette: navy `#22206A`, academy blue `#1B65A2`, cyan `#22B5E2`, white and very light blue.
