# Cost drivers of the proposed AWS staging/production design

No prices are quoted here: estimate them with the AWS Pricing Calculator for the chosen region on the day of the
decision, and record the estimate with the academy's budget approval (GO-LIVE-CHECKLIST, OPEN-QUESTIONS P0).

| Component (Terraform module) | Default size | Main cost driver | Cheaper option |
| --- | --- | --- | --- |
| NAT gateway (`network`) | 1 | Hourly charge + data processed (Zoom API, mail, ECR pulls) | An S3 gateway endpoint is always on (free); `interface_endpoints = true` adds ECR, Logs and Secrets Manager endpoints (hourly per zone) to cut NAT data |
| Load balancer (`compute`) | 1 ALB | Hourly + load-balancer capacity units | — |
| ECS Fargate (`compute`) | API 2 × (1 vCPU, 2 GB), web 2 × (0.5 vCPU, 1 GB), ARM64 | vCPU-hours and GB-hours | 1 task each outside class hours (scheduled scaling); size after the load test |
| RDS PostgreSQL (`database`) | db.t4g.small, 20–100 GB gp3, single-AZ, 7-day backups | Instance hours, storage, backup storage over the free allowance | Multi-AZ doubles the instance cost: choose by RTO |
| S3 (`storage`) | Exports 30 days, dumps 35 days | Storage and requests (small) | — |
| WAF (`compute`) | 1 web ACL, 3 rules (2 AWS managed groups + rate limit) | Per ACL, per rule and per million requests | — |
| AWS Backup (`database`) | Daily snapshot, kept 35 days | Snapshot storage | Fewer days (`db_snapshot_keep_days`) |
| CloudWatch (`compute`, `monitoring`) | Logs 30 days, 12 alarms, Container Insights | Log ingestion and storage, metrics | Shorter retention; Container Insights off |
| Secrets Manager | 6 secrets + the RDS master secret | Per secret per month | — |

There is no message queue: Zoom events are queued in PostgreSQL (`zoom_webhook_receipts`), which keeps them with the data
and needs no extra service at this size.

Sizing for 600 concurrent users must come from the load test (`load-tests/`, PERF-1) against staging before production.
