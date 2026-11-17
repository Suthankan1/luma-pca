# Contributing to LUMA
Read `AGENTS.md`. Use topic branches, small PRs, descriptive commits, and tests. Do not commit secrets or real student data. CI must pass before merge. Every feature PR should reference a user story, acceptance criteria, security implications, migration/rollback, and the updated checkpoint. See `.github/PULL_REQUEST_TEMPLATE.md` and `docs/delivery/DEFINITION-OF-DONE.md`.

## Protecting `main`
`main` has no branch protection yet; until it does, the rule above ("CI must pass before merge") is kept by process.
The repository owner can turn it on in Settings → Branches (or Rules → Rulesets) for `main`: require a pull request,
require these checks to pass (`checks`, `e2e`, `real-stack`, `test` from Backend tests, `validate` from terraform,
`build` from images), and block force-pushes and deletion. Not every workflow runs on every PR (they are filtered by
path), so require only checks that always run, or add path-independent summary jobs first.
