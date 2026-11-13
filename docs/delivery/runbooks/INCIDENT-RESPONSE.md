# Runbook — Incident response

Alarms go to the on-call addresses through SNS (`infra/terraform/modules/monitoring`). For each, the first steps:

| Alarm | Meaning | First steps |
| --- | --- | --- |
| `*-5xx` | More than 20 server errors in 5 minutes | ECS console → recent deployments; API log group for stack traces (no payloads are logged). If it started with a release, roll back (`DEPLOY.md`). |
| `*-api-unhealthy` | An API task fails `/actuator/health/readiness` for 3 minutes | Usually the database: RDS status, connections, `db-cpu`/`db-free-storage` alarms. ECS replaces the task. |
| `*-db-cpu`, `*-db-free-storage` | Database under load or filling | Performance Insights / slow-query log (statements over 1 s are logged). Storage autoscales to the configured maximum. |
| `*-zoom-dead-lettered` | A Zoom event could not be processed after retries | Zoom review → Failed events: read the error, fix the cause, replay with a reason (audited). See `WEBHOOK-INCIDENT.md`. |
| `*-zoom-report-failed` | Zoom participant reports keep failing | Zoom API credentials/scopes (`scripts/zoom-api-check.sh`), Zoom status page. Fetch again from Zoom review once fixed; attendance stays provisional meanwhile. |
| `*-zoom-dlq` | Messages in the SQS dead-letter queue (after the worker moves to SQS) | As above; redrive from the DLQ once fixed. |

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
