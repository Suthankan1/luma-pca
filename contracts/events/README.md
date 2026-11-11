# Events and payload conventions

The receipt endpoint (003B) stores the raw body as JSON without depending on fields beyond `event` and `event_ts`. `zoom-webhook-redacted-example.json` is *illustrative*, not evidence of live Zoom event shape. Exact payloads, recurrence identifiers, timestamps, webhook validation and entitlement-dependent fields must be verified against official docs and the academy's test webinar before binding DTOs. Store validated receipts with deterministic dedup keys and payload retention policy.
