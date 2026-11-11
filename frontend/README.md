# Next.js 16 frontend starter

This is **not** the production user interface yet. The routes `/`, `/staff` and `/student` are responsive design-preview shells, using fictional and explicitly labeled samples. They do not provide authentication, access to protected student data or live Zoom integration.

```bash
npm install
cp .env.local.example .env.local
npm run dev
npm run lint
npm run typecheck
npm run build
```

- Color tokens: `src/app/globals.css` — navy `#22206A`, blue `#1B65A2`, cyan `#22B5E2`, white.
- Figma: https://www.figma.com/design/QVzsewJyikM94Xp07hDwwi
- Existing HTML demo (design reference only): `../references/legacy-frontend-demo/`.
- Production BFF login, refresh and protected layouts must be implemented before showing real student or staff data. See `../docs/security/JWT-AUTH.md`.
