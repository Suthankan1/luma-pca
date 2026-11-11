# LUMA frontend

The staff and student portals use academy accounts provisioned in the Spring backend. Sign-in, session continuation and sign-out run through Next.js. A direct visit to a portal checks the account’s current roles with the backend.

```bash
npm ci
cp .env.local.example .env.local
node -p 'require("node:crypto").randomBytes(32).toString("base64url")'
npm run dev
```

Put the generated key in `LUMA_SESSION_KEY` in `.env.local` and start the Spring backend with test accounts. Use the exact URL in `LUMA_WEB_ORIGIN` to open the frontend. HTTP cookies are available for loopback development only. An HTTPS origin uses Secure, host-only cookies. Share the same session key across Next.js replicas and keep it separate from the backend signing and refresh keys.

When the access cookie expires, the sign-in page offers **Continue session** if the refresh cookie is available. Continuation is an explicit form submission so loading or prefetching a page cannot rotate the refresh token. Sign-out asks the backend to revoke the session and clears the browser cookies after success.

```bash
npm test
npm run lint
npm run typecheck
npm run build
npm run test:smoke
```

The portal pages are authenticated landing pages. Attendance, class schedules and account provisioning are separate slices.
