# LUMA frontend

The staff and student portals use academy accounts provisioned in the Spring backend. Sign-in, session continuation, sign-out, and multi-factor authentication (MFA) run through Next.js. A direct visit to a portal checks the account’s current roles with the backend.

```bash
npm ci
cp .env.local.example .env.local
node -p 'require("node:crypto").randomBytes(32).toString("base64url")'
npm run dev
```

Put the generated key in `LUMA_SESSION_KEY` in `.env.local` and start the Spring backend with test accounts. Use the exact URL in `LUMA_WEB_ORIGIN` to open the frontend. HTTP cookies are available for loopback development only. An HTTPS origin uses Secure, host-only cookies. Share the same session key across Next.js replicas and keep it separate from the backend signing and refresh keys.

### Authentication & MFA Flow

1. **Password Step:** `/api/session/login` sends credentials to the backend. Accounts without MFA receive session tokens immediately.
2. **MFA Challenge:** Elevated roles (owner and administrator) receive an MFA challenge response. The Next.js BFF encrypts the challenge into a short-lived, HttpOnly, SameSite=Strict cookie (`luma-mfa`) and redirects to `/sign-in/mfa`.
3. **Authenticator Enrollment:** If un-enrolled, the BFF fetches the setup secret once and generates a local vector QR code (no external requests or analytics). Manual secret entry is provided as an accessible fallback. Page refreshes preserve the unconfirmed secret without re-enrolling.
4. **Verification:** Users enter a 6-digit TOTP or recovery code. The BFF calls `/api/v1/auth/mfa/verify`. Upon success, authenticated session cookies are issued and the challenge cookie is cleared.
5. **Recovery Codes:** For initial enrollments, one-time backup recovery codes are displayed at `/sign-in/mfa/recovery-codes` with a copy-to-clipboard action. Explicit acknowledgment clears the temporary recovery state and redirects to `/staff`. Codes cannot be retrieved again by refreshing or navigating back.
6. **Cancellation & Expiry:** Users can cancel an MFA challenge at any time, returning to sign-in with challenge cookies wiped. Expired challenges require signing in again.

When the access cookie expires, the sign-in page offers **Continue session** if the refresh cookie is available. Continuation is an explicit form submission so loading or prefetching a page cannot rotate the refresh token. Sign-out asks the backend to revoke the session and clears all browser cookies after success.

```bash
npm test
npm run lint
npm run typecheck
npm run build
npm run test:smoke
npm run test:e2e
```

The portal pages are authenticated landing pages. Attendance, class schedules and account provisioning are separate slices.
