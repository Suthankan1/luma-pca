# Questions requiring academy confirmation (do not guess)

| Priority | Question | Why it matters |
| --- | --- | --- |
| P0 | How many active students, courses, batches and concurrent webinars? | Capacity/cost estimates and rate limits. **Partly answered 2026-11-12:** one webinar at a time, at most 500 students; capacity target 600 concurrent users. Totals of students, courses and batches still open |
| P0 | Which Zoom account license, Webinar add-on, report scopes, and registration policies exist? | Technical feasibility and identity mapping |
| P0 | Will academy mandate individual webinar registration linked to student ID/email? | Reliable matching without display names |
| P0 | Can staff approve a written attendance threshold and exception policy? | Fair decisions; late join/rejoin handling |
| P0 | Who owns domains, AWS account, Zoom account and admin access? | Production accountability |
| P0 | What is budget for hosting, monitoring, backups and maintenance? | Select realistic deployment |
| P0 | Is each student under 18? What consent/privacy/retention policy is applicable? | Legal and ethical handling |
| P1 | Can one webinar be shared among batches, courses or repeated occurrences? | Session mapping and deduplication |
| P1 | Can a student enroll in several batches of one course simultaneously? | Enrollment uniqueness rules |
| P1 | Would students use a separate portal for reactions? | The only reliably attributable emoji counts planned |
| P1 | Are leaderboards opt-in and visible to peers or only academy staff? | Privacy and anti-gaming design |
| P1 | Which reports, notification channels, languages and accessibility needs matter? | Scope and UX |
| P1 | What are uptime, RPO, RTO and retention requirements? | Hosting, backups, incident response |

**Do not enable real data collection until P0 questions are signed off.**

## Proposed class-information RAG extension — open decisions

The academy's endorsement of the idea/prototype in meeting three does not resolve these technical or privacy choices. Keep the assistant disabled until P0 decisions and UAT are signed off.

| Priority | Question | Why it matters |
| --- | --- | --- |
| P0 | Who uploads, reviews, and publishes course/batch/class source documents? | Prevent wrong or unapproved class information reaching students |
| P0 | Which documents are academy-wide vs enrollment-restricted? May prospective students use the assistant? | Model visibility and prevent cross-batch leaks |
| P0 | Which AI/embedding provider and data processing, retention, residency and training terms are approved? | External processing of prompts/document passages |
| P0 | What minors/guardian/consent, privacy notice and deletion rules apply to chatbot questions? | Student protection and compliance |
| P0 | What staff contact/WhatsApp route is approved for unanswered questions? | Reliable human fallback without inventing a contact |
| P1 | Which source is authoritative for live schedules if uploaded timetables disagree with class session records? | Avoid stale time/date answers |
| P1 | Which document formats, languages, sizes, update cadence and budget are acceptable? | Parsing quality and operations |

See [docs/rag/REQUIREMENTS.md](../rag/REQUIREMENTS.md) for full scope.
