# Runbook — Incident response

Alarms go to the on-call addresses through SNS (`infra/terraform/modules/monitoring`). For each, the first steps:

| Alarm | Meaning | First steps |
| --- | --- | --- |
| `*-5xx` | More than 20 server errors in 5 minutes | ECS console → recent deployments; API log group for stack traces (no payloads are logged). If it started with a release, roll back (`DEPLOY.md`). |
| `*-api-unhealthy` | An API task fails `/actuator/health/readiness` for 3 minutes | Usually the database: RDS status, connections, `db-cpu`/`db-free-storage` alarms. ECS replaces the task. |
| `*-db-cpu`, `*-db-free-storage` | Database under load or filling | Performance Insights / slow-query log (statements over 1 s are logged). Storage autoscales to the configured maximum. |
| `*-zoom-dead-lettered` | A Zoom event could not be processed after retries | Zoom review → Failed events: read the error, fix the cause, replay with a reason (audited). See `WEBHOOK-INCIDENT.md`. |
| `*-zoom-report-failed` | Zoom participant reports keep failing | Zoom API credentials/scopes (`scripts/zoom-api-check.sh`), Zoom status page. Fetch again from Zoom review once fixed; attendance stays provisional meanwhile. |
| `*-webhook-signature` | More than 10 Zoom webhooks an hour fail the signature check | The Zoom secret token in Secrets Manager matches the app's Features page? Server clock in sync? See `WEBHOOK-INCIDENT.md`. |
| `*-webhook-store` | A Zoom webhook could not be stored | Database first (as for `api-unhealthy`). Zoom retries failed deliveries for a limited time; anything missed can be recovered from Zoom's report (fetch again in Zoom review). |
| `*-retention` | The nightly privacy retention job failed | API log for the exception class; it runs again the next night. Data is kept a day longer meanwhile, nothing is lost. |
| `*-email` | More than 5 account emails failed in an hour | Mail provider status and the `mail` secret (username/password). Invitations retry on their own; people can request a reset again. |

Every log phrase these alarms count is checked by `AlarmLogPhrasesTest` in the backend tests, so rewording a log line
fails the build instead of silencing an alarm.

## Severity and communication
- **SEV1**: sign-in or classes unavailable for everyone, or suspected data exposure → owner and engineering lead at once;
  post a notice to staff; for a data exposure, follow the academy's privacy policy (OPEN-QUESTIONS P0) for notification.
- **SEV2**: one feature down (Zoom processing, email, exports) with a workaround → fix within the working day.
- **SEV3**: degraded or cosmetic → next release.

## Security incidents
- Suspected credential leak: rotate the secret in Secrets Manager, force a new deployment; for the JWT signing key, add the
  new public key to `LUMA_AUTH_VERIFICATION_KEYS_PEM` first, then switch the signing key, then remove the old one.
- A staff account misused: disable it in Staff (sessions end at once), review the activity log.
- Zoom webhook abuse: unsigned requests are already refused (401); rotate the Zoom secret token if signatures look valid.

## After every SEV1/SEV2
Write a short note in `docs/ai/checkpoints/` style: what happened, impact, timeline, cause, fix, follow-ups.
