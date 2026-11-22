# Portal sign-in and sign-out

The landing cards already select a portal. The sign-in page and session handler treated that choice as a preference and could send an account to the other portal. A second redirect in the browser also sent landing and sign-in tabs to the account's default portal. Sign-out returned to the shared sign-in screen. An expired refresh cookie could leave visitors on a continuation screen even after the API had refused renewal.

## Fix plan

1. Keep the selected portal through credential errors and enforce it against the roles reported by the API. Show the registration ID form for students when that mode is enabled. Use email and password for staff and for students when the mode is disabled.
2. Open the chosen portal immediately when the current session already grants access. If the browser is signed in to a different account, explain how to sign out and switch.
3. Revoke the backend session and clear the browser cookies on sign-out, then return to the landing page. Other open protected tabs also return there. Clear stale cookies when silent renewal on sign-in is refused with 401. Preserve retry during a service outage.
4. Verify both landing paths, wrong-portal credentials, stale sessions, sign-out, and re-entry using synthetic accounts.

## Expected flow

| Action | Result |
| --- | --- |
| Landing → Student sign in | Student form → student attendance if the account has student access |
| Landing → Staff sign in | Staff email and password → staff dashboard if the account has staff access |
| Valid credentials for the other portal | Stay on the selected sign-in screen with an access message |
| Already signed in to the other portal | Stay on the selected screen with a sign-out option |
| Sign out from either portal | Return to the landing page and choose either sign-in |
| Open the landing page while signed in | Renew the session if needed and open the account's default dashboard |
| Visit a protected page after sign-out | Redirect to its portal-specific sign-in |

## Verification and rollout

The production build, TypeScript, targeted ESLint, all 81 frontend unit tests, session HTTP smoke, and 12 focused Playwright desktop and mobile tests passed. The HTTP and browser checks used synthetic accounts with a mock API. A signed-in check against the real backend and database was outside this verification.

Roll out by rebuilding the frontend. The existing student sign-in setting still determines the student form. To roll back, revert the frontend fix and rebuild. There is no API or database migration.
