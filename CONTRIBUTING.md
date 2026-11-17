# Contributing to LUMA
Read `AGENTS.md`. Use topic branches, small PRs, descriptive commits, and tests. Do not commit secrets or real student data. CI must pass before merge. Every feature PR should reference a user story, acceptance criteria, security implications, migration/rollback, and the updated checkpoint. See `.github/PULL_REQUEST_TEMPLATE.md` and `docs/delivery/DEFINITION-OF-DONE.md`.

## Protecting `main`
Since 17 Nov 2026 `main` is protected: every change arrives by pull request, the GitGuardian secret scan must pass, force-pushes
and deleting `main` are refused, and this applies to administrators too. No approval is required (a single maintainer cannot
approve their own PR). The other workflows run only when their folders change, so they are not required checks: wait for them
to pass before merging. To require them as well, first add a summary job that runs on every PR.
