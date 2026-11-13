# Cost drivers of the proposed AWS staging/production design

No prices are quoted here: estimate them with the AWS Pricing Calculator for the chosen region on the day of the
decision, and record the estimate with the academy's budget approval (GO-LIVE-CHECKLIST, OPEN-QUESTIONS P0).

| Component (Terraform module) | Default size | Main cost driver | Cheaper option |
| --- | --- | --- | --- |
| NAT gateway (`network`) | 1 | Hourly charge + data processed (Zoom API, mail, ECR pulls) | VPC endpoints for ECR/S3/Secrets Manager reduce data processed |
| Load balancer (`compute`) | 1 ALB | Hourly + load-balancer capacity units | — |
| ECS Fargate (`compute`) | API 2 × (1 vCPU, 2 GB), web 2 × (0.5 vCPU, 1 GB), ARM64 | vCPU-hours and GB-hours | 1 task each outside class hours (scheduled scaling); size after the load test |
| RDS PostgreSQL (`database`) | db.t4g.small, 20–100 GB gp3, single-AZ, 7-day backups | Instance hours, storage, backup storage over the free allowance | Multi-AZ doubles the instance cost: choose by RTO |
| S3 (`storage`) | Exports 30 days, dumps 35 days | Storage and requests (small) | — |
| SQS (`messaging`) | 2 queues | Requests (small) | — |
| CloudWatch (`compute`, `monitoring`) | Logs 30 days, 9 alarms, Container Insights | Log ingestion and storage, metrics | Shorter retention; Container Insights off |
| Secrets Manager | 6 secrets + the RDS master secret | Per secret per month | — |

Sizing for 600 concurrent users must come from the load test (`load-tests/`, PERF-1) against staging before production.
