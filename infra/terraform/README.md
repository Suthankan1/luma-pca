# Infrastructure as code — validated, NOT APPLIED

`modules/` (network, database, storage, messaging, compute, monitoring) and `environments/staging` describe the proposed AWS
design of ADR-0006: private RDS PostgreSQL 17 (encrypted, TLS-only, backups, deletion protection), a private versioned S3
bucket (KMS, TLS-only, lifecycle), SQS with a DLQ, ECS Fargate for the web app and API behind an HTTPS load balancer (only
Zoom's webhook path reaches the API directly), secrets from Secrets Manager by ARN, CloudWatch logs and alarms to SNS.

CI (`.github/workflows/terraform.yml`) checks formatting and `terraform validate` on every change; it has no AWS credentials
and never plans or applies. **Applying creates chargeable resources**: only with the academy's budget approval, its AWS
account, a reviewed `plan`, and the steps in `docs/delivery/runbooks/DEPLOY.md`. Cost drivers: `docs/delivery/runbooks/COSTS.md`.

Secret values are never in Terraform or Git: Terraform creates empty secret containers; an operator fills them.
