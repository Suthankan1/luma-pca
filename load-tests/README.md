# Load tests

k6 scripts for the scenarios in [docs/testing/LOAD-TEST-PLAN.md](../docs/testing/LOAD-TEST-PLAN.md):

| Script | Scenario |
| --- | --- |
| `k6/sign-in-burst.js` | L1/L5: students sign in through the web app at class start and use the portal |
| `k6/webhook-burst.js` | L2: signed Zoom webhook bursts (joins, reconnects, leaves) |
| `k6/staff-reads.js` | L3: instructors reading rosters and the class during the burst |

Run all three locally with synthetic data:

```bash
STUDENTS=150 scripts/load-test-local.sh                       # quick check, about 2 minutes
STUDENTS=600 scripts/load-test-local.sh                       # the target class (600), about 17 minutes
STUDENTS=600 PER_ADDRESS=50 SCENARIOS=sign-in scripts/load-test-local.sh    # carrier-NAT variant
```

**Local results are a baseline, never capacity evidence**: everything shares one machine.
The scripts also run unchanged against a production-like environment (set `BASE_URL`,
`WEB_ORIGIN`, `API_URL` and the credentials), which is what the capacity gate requires.
The "Load test (local baseline)" GitHub workflow runs the script on demand on a GitHub runner.
