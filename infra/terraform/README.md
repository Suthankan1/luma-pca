# Infrastructure-as-code plan — NOT PROVISIONED

These directories map the proposed AWS target, **not** a deployable Terraform implementation. Never execute a fake or partial Terraform `apply` against the academy account. Resolve cost estimate, region, privacy obligations, VPC/NAT choices, availability targets and ownership before adding resources.

Suggested modules to implement iteratively with review:
1. Network: VPC, private subnets, NAT/cost choice, security groups, ALB.
2. Database: encrypted RDS PostgreSQL, migrations, security groups, automated backups, restore drills.
3. Compute: ECS/Fargate API and separate worker task, task roles and auto-scaling.
4. Messaging: SQS + DLQ, idempotence and monitored queue age.
5. Storage: private S3 report exports, KMS, retention, presigned URL controls.
6. Secrets: Zoom S2S tokens and JWT keys in Secrets Manager/KMS, least privilege.
7. Telemetry: CloudWatch dashboards/alarms, traces, notification routes.
8. GitHub OIDC deployment with reviewable dev/staging/prod environments.

Start with a small local proof and a reviewed cost plan, not Terraform scaffolding that appears to be production-ready.
