# Runbook — First deployment and releases (AWS, ADR-0006)

**Status:** written and validated as code (`infra/terraform`, CI job `terraform`), **not applied**. Applying creates
chargeable AWS resources: do it only with the academy's budget approval, its own AWS account and a reviewed plan.

## Before the first apply
1. Academy decisions: AWS account owner, region (default `ap-south-1`, Mumbai; confirm data residency), domain, budget
   (see `COSTS.md`), RPO/RTO (sets `db_multi_az`, `db_backup_retention_days`), export retention, on-call addresses.
2. ACM certificate for the domain in the region (DNS validation).
3. Container images: build `backend/Dockerfile` and `frontend/Dockerfile` for `linux/arm64`, push to ECR, note the tags.
4. Copy `infra/terraform/environments/staging/staging.tfvars.example` to `staging.tfvars` (gitignored) and fill it in.
5. Remote state: create a versioned, encrypted S3 bucket and uncomment the `backend "s3"` block.

## Apply
```bash
cd infra/terraform/environments/staging
terraform init
terraform plan -var-file=staging.tfvars -out=plan.bin   # review with the academy before applying
terraform apply plan.bin
```

## After the first apply (secrets are never in Terraform or Git)
Put values into the secret containers Terraform created (`luma-staging/...`):
- `auth-signing-key` (PEM, ES256 private key), `auth-mfa-encryption-key`, `session-key` (32 random bytes, base64url);
- `zoom-api` (JSON `account_id`, `client_id`, `client_secret`), `zoom-webhook` (the Zoom app's secret token);
- `mail` (JSON `username`, `password`).
Then `aws ecs update-service --force-new-deployment` for `api` and `web`, wait for both to be healthy, and:
1. Create the first owner: `scripts/bootstrap-owner.sh` against the database (see `FIRST-OWNER.md`).
2. Point the domain at the load balancer (`load_balancer_dns` output).
3. In the Zoom app, set the event subscription to `zoom_webhook_url` and validate it (see `docs/integration/ZOOM-TEST-WEBINAR.md`).
4. Run the restore drill against a fresh snapshot (`RESTORE.md`) and record the result.

## Releases
- Build and push new images with a new tag; change `api_image`/`web_image`; `terraform plan` then `apply`.
- ECS deploys with a circuit breaker and rolls back automatically if the new tasks never become healthy.
- Database migrations run when the API starts (Flyway). They are forward-only: take a manual RDS snapshot before a
  release that contains a migration.

## Rollback
1. Set the previous image tags and apply (or `aws ecs update-service --task-definition <previous revision>`).
2. If a migration must be undone, restore the pre-release snapshot to a new instance (`RESTORE.md`) and switch `DB_URL`;
   never edit Flyway history by hand.
3. Feature switches for a fast, reversible stop: `LUMA_ZOOM_PROCESSING_ENABLED=false` (stop processing; receipts still
   stored), `LUMA_ZOOM_REPORTS_ENABLED=false`, `LUMA_MAIL_BULK_ENABLED=false`, engagement off in the Attendance page, and
   removing the Zoom webhook secret (the endpoint then answers 404 and Zoom retries later).

## Email through Gmail (Oracle VM)

The backend sends every email (invitations, password resets, attendance notices), so the settings go in the backend's
environment file on the VM (the one `luma-backend.service` loads), never in Vercel or Git.

1. In the academy's sending Gmail account: turn on 2-Step Verification, then Google Account → Security → App passwords →
   create one for "LUMA". Copy the 16-character code (no spaces).
2. On the VM, add to the environment file (keep it `chmod 600`, owned by the service user):
   ```
   LUMA_MAIL_ENABLED=true
   SPRING_MAIL_HOST=smtp.gmail.com
   SPRING_MAIL_PORT=587
   SPRING_MAIL_USERNAME=<the Gmail address>
   SPRING_MAIL_PASSWORD=<the app password>
   LUMA_MAIL_FROM=<the same Gmail address>
   LUMA_MAIL_WEB_ORIGIN=https://luma-pca.vercel.app
   LUMA_MAIL_STARTTLS_REQUIRED=true
   LUMA_MAIL_BULK_BATCH_SIZE=10
   LUMA_MAIL_BULK_POLL_INTERVAL=PT60S
   ```
3. `sudo systemctl restart luma-backend`, then `journalctl -u luma-backend -n 50`: no mail errors at startup.
4. Check: Sign-in → "Forgot your password" for an account you control; the email arrives within a minute and its link
   opens the web app once. Then invite one test student and check the inbox (and spam, once).

STARTTLS and 10-second timeouts are set in `application.yml`; SMTP login is switched on automatically when a username is
set. Gmail allows roughly 500 recipients a day on a personal account (about 2,000 on Google Workspace): invite batch by
batch. Failures are logged as "… email for account … failed" and listed under failed invitations; they retry on their own.
