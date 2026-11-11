# ADR-0006 — Proposed AWS deployment
**Status:** Proposed, not yet approved by academy
**Proposal:** Next.js frontend hosted on Vercel or AWS; Spring Boot API and eventual worker on ECS/Fargate; private RDS PostgreSQL; SQS + DLQ; private S3 exports; Secrets Manager; CloudWatch, tracing, GitHub Actions/Terraform.
**Gates:** verify academy budget, staff capacity, backup/recovery RPO/RTO, regional compliance, concurrency and Zoom licensing. A smaller managed-host deployment may be selected to reduce fixed costs. No AWS resource in this starter is provisioned.
