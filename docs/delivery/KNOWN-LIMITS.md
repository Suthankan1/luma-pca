# Known limits (17 Nov 2026)

What LUMA deliberately does not do, and what could not be proven from a developer's machine. Each item says what to do
about it.

## By design

| Limit | Why | What to do |
| --- | --- | --- |
| **Zoom Webinars only, not Zoom Meetings.** | The academy teaches in webinars; meetings send different events and reports. | Run classes as webinars. Adding meetings would be a new slice (meeting events, `report/meetings`). |
| **One academy per deployment.** | Zoom webhooks carry no academy, so the deployment's `LUMA_ZOOM_WEBHOOK_ACADEMY_ID` names it. | A second academy gets its own deployment and Zoom app. |
| **Registration links (`…/register/WN_…`) cannot be checked.** | The `WN_` code is not the webinar ID and Zoom offers no lookup from it with these scopes. | Paste the join link or copy the webinar ID from Zoom (the Check page explains this when given one). |
| **Attendees Zoom gives no email for cannot be matched.** | Someone who joins without signing in to Zoom and without registering has no email in Zoom's data. | Keep "registration required" (with Zoom sign-in) on class webinars, as on the test webinar. |
| **Connected time is not attention.** | Zoom reports connection only. | Engagement (reactions, questions) is separate, opt-in and never part of attendance. |
| **Zoom's emoji reactions are not used.** | Zoom does not say who reacted. | Portal reactions are used instead. |
| **Students under 18.** | The academy confirmed attendance data only is collected, with guardians' agreement. | Nothing in the app; recorded in OPEN-QUESTIONS. |

## Not provable without a deployment or more real webinars

| Item | Status | What proves it |
| --- | --- | --- |
| **Load at 600 people** | Measured with 1,500 simulated students on one shared 4-vCPU runner (k6, API, web and database together); a real N+1 query was found and fixed, but that setup cannot give a capacity figure (LOAD-TEST-PLAN §10). | Run `load-tests/` against staging (LOAD-TEST-PLAN). |
| **Recurring webinars with several occurrences in real Zoom** | Tested with fixtures and one real webinar (one occurrence). | Run two occurrences of one real recurring webinar and check Zoom review links each to its class. |
| **Alarms, WAF, backups in AWS** | Terraform validated, never applied. | `terraform plan`/`apply` in the academy's account (DEPLOY.md). |

## Operating notes

- **Zoom app → Features → Event Subscriptions:** turning the subscription switch off **deletes** the subscriptions; it
  does not pause them. To stop events, change the endpoint or leave it on and ignore them. Re-creating needs the event
  list in ZOOM-TEST-WEBINAR.md and a new endpoint validation.
- **Branch protection** for `main` is available but off; see CONTRIBUTING.md.
- **Notion status page** ("02 · Current Build Status") stopped at PR #63 because the connector was turned off. This
  repository's PROGRESS.md is the record.
- **PR #71** (RAG class information assistant plan) is the repository owner's own draft and is not part of this delivery.
