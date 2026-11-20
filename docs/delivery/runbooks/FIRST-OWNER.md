# Operator runbook — Creating an academy's first owner

Owners and administrators invite everyone else from the staff screens. The one account
nobody can invite is an academy's first owner, so it is created with an operator command.
There is no HTTP route for this: the command needs the same database access as the API.

## Steps

1. Build the backend (`mvn -f backend/pom.xml package`) or use the released jar
   (`LUMA_BACKEND_JAR=/path/to/luma-backend.jar`).
2. In a terminal whose output is **not** collected into logs, set the API's environment:
   `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, the signing key, and either
   `LUMA_BOOTSTRAP_WEB_ORIGIN=https://<web app origin>` (for a printed link) or the
   `LUMA_MAIL_*` and `SPRING_MAIL_*` settings (to email it).
3. Run:

   ```bash
   scripts/bootstrap-owner.sh "Physics Cube Academy" owner@academy.example          # prints the link
   scripts/bootstrap-owner.sh "Physics Cube Academy" owner@academy.example EMAIL    # emails it
   ```

4. Send a printed link to the owner privately. It is a credential: it works once and
   expires after 72 hours. The owner opens it, chooses a password and signs in with
   their email and that password.

## What the command does

- Creates the academy if no academy has that exact name (more than one with the name is
  refused), and a pending owner account with that email.
- Run again **before** the owner activates (for example after the link expired), it issues
  a new link for the same address and cancels the old one.
- It **refuses** when the academy already has an active owner, when an invitation is pending
  for a different address, or when the address belongs to another account. It never
  replaces, re-enables or resets an existing owner.
- It records `OWNER_BOOTSTRAPPED` in the audit trail with no actor (the operator) and
  without the address or link. The link is printed to standard output only, never logged.

Exit codes: 0 done, 1 refused (reason printed), 2 bad arguments or settings, 3 saved but
the email could not be sent (run again with PRINT, or fix email and run again).

## Not covered

Recovering an academy whose every owner has lost access to their password and email
is not automated. Owners can reset their own password by email; losing access to the
mailbox as well needs a reviewed manual procedure.
