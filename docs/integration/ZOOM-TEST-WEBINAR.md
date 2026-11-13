# Runbook — First real Zoom test webinar (slice 003A)

Goal: receive real Zoom webhook events for **one test webinar** on this Mac, to learn exactly
what Zoom sends (participant fields, occurrence IDs, reconnects, timing) before building
attendance. Fixture tests cannot show that.

## Safety rules

- The Zoom event subscription covers **the whole account**, including real classes. The
  tunnel script therefore refuses to start without `LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST`;
  events for any other webinar are acknowledged and dropped unread (not stored, not logged).
- Use a test webinar with **test participants only** (staff or volunteers who agree), never
  real students, until the academy signs off the privacy questions in
  `docs/product/OPEN-QUESTIONS.md`.
- Only `POST /api/v1/zoom/webhooks` is reachable from the internet, through an nginx proxy
  and a temporary Cloudflare quick tunnel. Stop the script after the test; the URL dies with it.
- Secrets live only in `.env.zoom-test` (gitignored, mode 600). Never paste them into chat,
  tickets or commits.
- **Do not let browser automation read the app's Features or App Credentials pages.** Zoom puts
  the Secret Token inside the token field's accessible label, so any element search or page
  read there returns it. Do the event-subscription step by hand, or regenerate the token
  afterwards.
- The test owner's activation link is written to `.env.zoom-test-owner-link` (gitignored,
  mode 600), never to the screen.

## Steps

1. **Zoom app (Marketplace → Develop → Build App → Server-to-Server OAuth)**, separate from
   any existing app, named for example "LUMA Attendance (test)". Then store its values with:

   ```bash
   scripts/zoom-test-setup.sh
   ```

   It asks for the **Secret Token** (Features page), the test webinar ID, and optionally the
   Account ID, Client ID and Client Secret (App Credentials page; not used yet), at hidden
   prompts, and writes `.env.zoom-test` readable by you only. The file is gitignored; the
   tracked template is `.env.zoom-test.example`. Nobody else (including an AI assistant)
   needs to see these values.

2. **Schedule the test webinar** (or pick one that only test participants attend) and put
   its ID in the allow-list above.
3. **Start the tunnel** (needs Docker, JDK 21, built backend and frontend):

   ```bash
   scripts/zoom-test-tunnel.sh
   ```

   It prints the webhook URL (`https://…trycloudflare.com/api/v1/zoom/webhooks`) and, on the
   first run, a link to set the test owner's password for the review screen.
4. **Event subscription** on the app's Features page: add the URL, then choose the webinar
   events LUMA stores: webinar started, webinar ended, participant joined webinar,
   participant left webinar (`webinar.started`, `webinar.ended`,
   `webinar.participant_joined`, `webinar.participant_left`). Add the scopes Zoom says these
   events need, nothing more. Click **Validate**: Zoom sends a signed `endpoint.url_validation`
   and LUMA answers it. Save, then **activate** the app.
5. **Check the API side** (optional, read-only):

   ```bash
   scripts/zoom-api-check.sh
   ```

   It obtains a token with the app's credentials and reads the test webinar: granted scopes,
   webinar facts, registrant counts and, once it has run, join sessions per person (counts only;
   no names or emails). Webinars outside the allow-list are refused before any call.

6. **Run the test webinar** with a few test participants: join, leave, rejoin, join from a
   phone, join late, leave early.
7. **Review**: `scripts/zoom-test-tunnel.sh summary`, `scripts/zoom-api-check.sh`, and the Zoom review page at
   `http://127.0.0.1:3400/staff/zoom` (link the occurrence to a class there if needed).
8. **Stop** the script (Ctrl+C). Zoom's "Event Subscriptions" switch does not pause a subscription: turning it off
   **removes** every subscription and its settings. Leaving it on is harmless while the tunnel is down (deliveries to the
   dead URL fail and Zoom gives up); paste the new URL and validate it before the next test.

## Verified before the first real test

With a throwaway token, over the live tunnel URL: every other path answered 404 (GET on the
webhook path 403); Zoom-style URL validation returned the correct signed token; a signed
join for the allowed webinar was stored and processed into an occurrence; a signed join for
another webinar was acknowledged but not stored; an unsigned one got 401.

## Keep the Mac awake

A sleeping Mac loses the quick tunnel: Cloudflare forgets the name ("Tunnel not found") and the
public URL stops working while the local services keep running. Start the tunnel with
`caffeinate -i scripts/zoom-test-tunnel.sh` to prevent idle sleep for as long as it runs (a
closed lid still sleeps). After any restart, paste the new URL into the Zoom app's event
subscription and validate it.

## If the terminal running the tunnel was closed

Closing the shell that ran the script (for example an app restart) ends the backend and web app
but can leave the PostgreSQL, proxy and Cloudflare containers running, so the public URL answers
404 for everything while webhooks fail behind it. Run `caffeinate -i scripts/zoom-test-tunnel.sh resume`:
it restarts only the backend and web app behind the running tunnel, so the URL — and the Zoom
subscription — stay the same. (This happened on 13 Nov at about 01:20 Colombo and was resumed this way.)

## If Cloudflare drops the quick tunnel while the Mac is awake

On 13 Nov at 10:59 Colombo the public URL answered Cloudflare error 1033 (530) although the
connector container logged a registered connection and no errors, and the lid had stayed open.
That hostname never comes back. `docker restart luma-zoom-test-tunnel` gives a new
`trycloudflare.com` URL in seconds behind the same proxy, backend and database; paste it into the
Zoom subscription, **Validate**, **Save**, then reload the page and reopen the subscription to
confirm it stuck (a validated but unsaved URL is silently discarded, which also happened once).
Check the public URL a few minutes before every test: an unsigned `POST` must answer 401.

## Findings from the first real test webinar (13 Nov 2026)

Webinar 838 3442 8148 (type 5, registration with Zoom sign-in required), started 11:28:31
Colombo, ended by the host at 11:32:30. Host plus three test attendees; one attendee left and
rejoined. Personal values were compared only as hashes; none were printed or recorded here.

- **Delivery:** 12 events (1 `webinar.started`, 5 `participant_joined`, 5 `participant_left`,
  1 `webinar.ended`), each stored once and processed on the first attempt into one occurrence.
  No duplicates in this run (duplicates remain possible and are still handled).
- **Agreement with the API:** `zoom-api-check.sh` after the end reported 3 approved registrants,
  1 past instance and "5 join sessions by 4 people", exactly the webhook joins (host + 3, one twice).
- **Join/leave pairing:** every join had a leave with the same `participant.user_id`; `user_id`
  and `participant_uuid` are new on each rejoin, so they identify a visit, not a person.
- **Person identity:** `registrant_id` and `email` were identical across one attendee's leave and
  rejoin, and present on every attendee event (registration plus Zoom sign-in required).
  `participant_user_id` and `id` were empty for attendees and set only for the host.
- **Leave reasons:** free text, e.g. "left the meeting", "Host ended the meeting",
  "Host closed the meeting"; when the host ends, attendees get leaves at the end time.
- **Personal data in payloads:** join/leave events carry `user_name`, `email`, `public_ip` and
  (on leave) `private_ip`; the receiver stores the whole payload. IP addresses are not needed for
  attendance and should be dropped before storage before any real student joins (privacy, P0).
- **Not linked to a class:** no class was scheduled on this webinar in the test academy, so the
  occurrence waits in review, as designed.
