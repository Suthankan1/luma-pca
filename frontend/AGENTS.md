# Frontend-specific instructions
Read `../AGENTS.md` first. Next.js 16 App Router with TypeScript and Tailwind 4. Current UI is **an explicitly labeled bootstrap preview** with fictional data. Do not pass fake data as real attendance. Phase 1 must add real JWT BFF with secure HttpOnly refresh cookie, server-side access tokens, CSRF protections, and role scoped redirects. No localStorage tokens; never put Zoom OAuth secrets in Next.js `NEXT_PUBLIC_*`. Preserve Physics Cube Academy colors and accessible mobile UI. Reference `../references/legacy-frontend-demo` for legacy design only, not auth or business logic.

<!-- BEGIN:nextjs-agent-rules -->

## This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
