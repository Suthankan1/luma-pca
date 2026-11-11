# Physics Cube Academy design system

- **Logo reference:** `physics-cube-academy-logo.jpg` (provided by user; use within academy project subject to their approval).
- **Figma original:** https://www.figma.com/design/QVzsewJyikM94Xp07hDwwi
- **Colors (logo-inspired approximate values):** navy `#22206A`, academic royal blue `#1B65A2`, cyan `#22B5E2`, white `#FFFFFF`; light surface `#F2F8FD`, ink `#1E2C67`, muted `#587695`.
- **Visual style:** clear, editorial and trustworthy; purposeful large numerals and attendance timelines; restrained cards; not a generic gradient-heavy 'AI dashboard'. Use consistent responsive grids and readable contrast.
- **Frontend bootstrap:** `frontend/src/app/globals.css` theme variables. Align shared components and typography with Figma during the actual UI migration.
- **Responsive design:** 1440px desktop reference; 390px mobile; touch targets ideally ≥44px; consistent aria labels, keyboard support, focus states and reduced-motion support.
- **Data truth:** show `SAMPLE` for fictional values, `PROVISIONAL` for event-only attendance, `RECONCILED` for final attendance, `REVIEW REQUIRED` for uncertainty, and `UNVERIFIED` for unavailable source-specific attribution.
- **Original HTML prototype:** `references/legacy-frontend-demo/` is a design reference with simulated data and no production auth. Do not ship its simulated logic as production.
