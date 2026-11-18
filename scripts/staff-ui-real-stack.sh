#!/bin/bash
# Real-stack browser test for the staff screens: throwaway PostgreSQL 17 + the built
# Spring backend jar + a production build of Next.js, driven by Playwright
# (frontend/tests/real-stack). The academy and its owner come from
# scripts/bootstrap-owner.sh; only Zoom occurrences and a failed receipt (no personal
# data) are seeded with SQL for the review screen; everything else happens through the browser. Invitation
# and password reset emails go over SMTP to a Mailpit container (ports 11025 and 18025).
#
# Needs: Docker running, JDK 21 as `java` (or JAVA_HOME), Node 22+, a built backend
# (`mvn -f backend/pom.xml package`), `npm --prefix frontend ci` and
# `npm --prefix frontend run build`. Uses ports 18081, 3200 and 55433.
# Runs in CI as the "Real-stack browser test" workflow (.github/workflows/real-stack.yml).
# All credentials are generated per run.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$(mktemp -d)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PG=luma-staff-ui-pg; MAIL=luma-staff-ui-mail; API=18081; WEB=3200; ORIGIN=http://127.0.0.1:$WEB
# Mailpit catches every email over real SMTP and shows it through its HTTP API.
MAILPIT_IMAGE=axllent/mailpit:v1.27.0; SMTP=11025; MAILPIT=18025
DBPASS=$(openssl rand -hex 12)
PHRASE="$(openssl rand -hex 4) river under bridge"
cleanup() { [ -n "${BPID:-}" ] && kill $BPID 2>/dev/null; [ -n "${FPID:-}" ] && kill $FPID 2>/dev/null; docker rm -f $PG $MAIL >/dev/null 2>&1; rm -rf "$S"; }
trap cleanup EXIT

docker rm -f $PG $MAIL >/dev/null 2>&1
docker run -d --rm --name $MAIL -p 127.0.0.1:$SMTP:1025 -p 127.0.0.1:$MAILPIT:8025 $MAILPIT_IMAGE >/dev/null
docker run -d --rm --name $PG -e POSTGRES_USER=luma -e POSTGRES_PASSWORD=$DBPASS -e POSTGRES_DB=luma -p 127.0.0.1:55433:5432 postgres:17-alpine >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
DB_URL=jdbc:postgresql://localhost:55433/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS SERVER_PORT=$API LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true \
  LUMA_ZOOM_PROCESSING_ENABLED=false LUMA_SESSIONS_MANUAL_SCHEDULING=true LUMA_MAIL_ENABLED=true LUMA_MAIL_FROM=no-reply@example.invalid \
  LUMA_MAIL_WEB_ORIGIN=$ORIGIN LUMA_RATE_LIMIT_RESET_REQUEST_LIMIT=3 LUMA_MAIL_BULK_POLL_INTERVAL=PT1S SPRING_MAIL_HOST=127.0.0.1 SPRING_MAIL_PORT=$SMTP "$JAVA" -jar "$ROOT/backend/target/luma-backend-0.1.0-SNAPSHOT.jar" > "$S/backend.log" 2>&1 & BPID=$!
for i in $(seq 1 90); do curl -sf localhost:$API/actuator/health/readiness >/dev/null && break; sleep 1; done
curl -sf localhost:$API/actuator/health/readiness >/dev/null || { echo "backend did not start"; tail -20 "$S/backend.log"; exit 1; }

# The academy and its first owner come from the operator command, exactly as in production.
bootstrap() {
  DB_URL=jdbc:postgresql://localhost:55433/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true \
    LUMA_BOOTSTRAP_WEB_ORIGIN=$ORIGIN "$ROOT/scripts/bootstrap-owner.sh" "Real Stack Academy" smoke.admin@example.invalid > "$1" 2>&1
}
bootstrap "$S/bootstrap.out" || { echo "owner bootstrap failed:"; grep "LUMA owner bootstrap" "$S/bootstrap.out"; exit 1; }
INVITE=$(grep -oE "^$ORIGIN/activate#token=[A-Za-z0-9_-]{43}$" "$S/bootstrap.out" | head -1 | sed 's/.*token=//')
[ -n "$INVITE" ] || { echo "the bootstrap printed no activation link"; grep "LUMA owner bootstrap" "$S/bootstrap.out"; exit 1; }
# The link is printed once, on its own line, and appears in no log line.
[ "$(grep -c -F -e "$INVITE" "$S/bootstrap.out")" = 1 ] || { echo "the invitation token appeared more than once in the command output"; exit 1; }
ACADEMY_ID=$(docker exec $PG psql -At -U luma -d luma -c "SELECT id FROM academies WHERE name = 'Real Stack Academy'")
docker exec -i $PG psql -q -U luma -d luma -v ON_ERROR_STOP=1 <<SQL
-- Zoom data as the (disabled) worker would have left it, without personal data: an occurrence on the
-- webinar the test's class will use (13 Jan 2027 18:10 Colombo), one on an unknown webinar, and a
-- dead-lettered receipt. Webhook ingress and processing stay off for this run.
INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at, processing_status, attempts, processed_at) VALUES
  ('66666666-6666-6666-6666-000000000001', '$ACADEMY_ID', 'webinar.started', 1799844600000, repeat('1', 64), '{}', NOW(), 'PROCESSED', 1, NOW()),
  ('66666666-6666-6666-6666-000000000002', '$ACADEMY_ID', 'webinar.started', 1799812800000, repeat('2', 64), '{}', NOW(), 'PROCESSED', 1, NOW());
INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at, processing_status, attempts, last_error) VALUES
  ('66666666-6666-6666-6666-000000000003', '$ACADEMY_ID', 'webinar.participant_left', 1799848800000, repeat('3', 64), '{}', NOW(), 'DEAD', 10, 'Seeded failure for the real-stack test');
INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, event_type, event_ts, created_at) VALUES
  ('77777777-7777-7777-7777-000000000001', '$ACADEMY_ID', '66666666-6666-6666-6666-000000000001', '81234567890', 'realAuto/AAAAAAAAAAAA==', 'webinar.started', 1799844600000, NOW()),
  ('77777777-7777-7777-7777-000000000002', '$ACADEMY_ID', '66666666-6666-6666-6666-000000000002', '55500011101', 'realManual+AAAAAAAAAA==', 'webinar.started', 1799812800000, NOW());
-- A past run (9 Mar 2026, 18:00–20:00 Colombo) on webinar 833 0001 1100 for the attendance step: live joins of the test
-- student and a guest, and Zoom's participant report as the report fetcher stores it (test identities only).
INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at, processing_status, attempts, processed_at) VALUES
  ('66666666-6666-6666-6666-000000000011', '$ACADEMY_ID', 'webinar.started', 1773059700000, repeat('4', 64), '{}', NOW(), 'PROCESSED', 1, NOW()),
  ('66666666-6666-6666-6666-000000000012', '$ACADEMY_ID', 'webinar.participant_joined', 1773059760000, repeat('5', 64), '{}', NOW(), 'PROCESSED', 1, NOW()),
  ('66666666-6666-6666-6666-000000000013', '$ACADEMY_ID', 'webinar.participant_joined', 1773063600000, repeat('6', 64), '{}', NOW(), 'PROCESSED', 1, NOW()),
  ('66666666-6666-6666-6666-000000000014', '$ACADEMY_ID', 'webinar.ended', 1773066600000, repeat('7', 64), '{}', NOW(), 'PROCESSED', 1, NOW());
INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, event_type, event_ts, created_at, visit_key, participant_email, participant_name) VALUES
  ('77777777-7777-7777-7777-000000000011', '$ACADEMY_ID', '66666666-6666-6666-6666-000000000011', '83300011100', 'realPast/AAAAAAAAAAAA==', 'webinar.started', 1773059700000, NOW(), NULL, NULL, NULL),
  ('77777777-7777-7777-7777-000000000012', '$ACADEMY_ID', '66666666-6666-6666-6666-000000000012', '83300011100', 'realPast/AAAAAAAAAAAA==', 'webinar.participant_joined', 1773059760000, NOW(), 'v-student', 'real.student@example.invalid', 'Real Student'),
  ('77777777-7777-7777-7777-000000000013', '$ACADEMY_ID', '66666666-6666-6666-6666-000000000013', '83300011100', 'realPast/AAAAAAAAAAAA==', 'webinar.participant_joined', 1773063600000, NOW(), 'v-guest', 'real.guest@example.invalid', 'Visiting Guest'),
  ('77777777-7777-7777-7777-000000000014', '$ACADEMY_ID', '66666666-6666-6666-6666-000000000014', '83300011100', 'realPast/AAAAAAAAAAAA==', 'webinar.ended', 1773066600000, NOW(), NULL, NULL, NULL);
INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions, webinar_id) VALUES
  ('$ACADEMY_ID', 'realPast/AAAAAAAAAAAA==', 'FETCHED', 1, NOW(), 2, '83300011100');
INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, joined_at, left_at, duration_seconds) VALUES
  ('88888888-8888-8888-8888-000000000001', '$ACADEMY_ID', 'realPast/AAAAAAAAAAAA==', 'real.student@example.invalid', 'Real Student', '2026-03-09T12:36:00Z', '2026-03-09T14:25:00Z', 6540),
  ('88888888-8888-8888-8888-000000000002', '$ACADEMY_ID', 'realPast/AAAAAAAAAAAA==', 'real.guest@example.invalid', 'Visiting Guest', '2026-03-09T13:40:00Z', '2026-03-09T14:25:00Z', 2700);
SQL

cd "$ROOT/frontend"
# One trusted proxy in front of Next.js: the test's browsers send X-Forwarded-For as a load balancer would.
LUMA_TRUSTED_PROXY_HOPS=1 LUMA_WEB_ORIGIN=$ORIGIN LUMA_SESSION_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=') API_INTERNAL_URL=http://127.0.0.1:$API \
  node node_modules/next/dist/bin/next start -H 127.0.0.1 -p $WEB > "$S/frontend.log" 2>&1 & FPID=$!
for i in $(seq 1 60); do curl -sf -o /dev/null $ORIGIN/sign-in && break; sleep 1; done

for i in $(seq 1 30); do curl -sf -o /dev/null http://127.0.0.1:$MAILPIT/api/v1/messages && break; sleep 1; done
LUMA_REAL_STACK_URL=$ORIGIN LUMA_REAL_API_URL=http://127.0.0.1:$API LUMA_REAL_MAILPIT_URL=http://127.0.0.1:$MAILPIT LUMA_REAL_ADMIN_INVITE=$INVITE LUMA_REAL_PASSPHRASE=$PHRASE \
  npx playwright test -c playwright.real-stack.config.ts
status=$?

# Once the owner is active, the command refuses to add another.
bootstrap "$S/bootstrap-again.out"; again=$?
echo "bootstrap again: exit $again, $(grep -o 'already has an active owner' "$S/bootstrap-again.out")"
[ "$again" = 1 ] || status=1

echo "--- database after the run"
docker exec $PG psql -At -U luma -d luma -c "SELECT 'courses=' || count(*) FROM courses" -c "SELECT 'batches=' || count(*) FROM batches" \
  -c "SELECT 'students=' || count(*) FROM students" -c "SELECT 'enrollments=' || count(*) || ' withdrawn=' || count(withdrawn_on) FROM enrollments" \
  -c "SELECT 'active accounts=' || count(*) FROM accounts WHERE status = 'ACTIVE'" \
  -c "SELECT 'staff assignments=' || count(*) FROM staff_assignments" \
  -c "SELECT 'class sessions=' || string_agg(starts_at::text || ' to ' || ends_at::text || ' webinar ' || zoom_webinar_id, ';') FROM class_sessions" \
  -c "SELECT 'occurrence links=' || string_agg(occurrence_uuid || ':' || link_source || ':' || coalesce(class_session_id::text, 'not a class'), ',') FROM zoom_occurrence_links" \
  -c "SELECT 'receipts=' || string_agg(processing_status || ':' || attempts, ',' ORDER BY id) FROM zoom_webhook_receipts" \
  -c "SELECT 'password resets=' || count(*) || ' used=' || count(consumed_at) FROM password_resets" \
  -c "SELECT 'limit counters=' || string_agg(bucket || ':' || client || ':' || hits, ',' ORDER BY bucket, client) FROM rate_limit_counters" \
  -c "SELECT 'audit: ' || string_agg(action, ',' ORDER BY occurred_at) FROM audit_events"
echo "emails caught: $(curl -sf http://127.0.0.1:$MAILPIT/api/v1/messages | grep -o '"total":[0-9]*')"
# Reset links and recipient addresses must never reach the logs either.
leaks=$(grep -c -E "eyJ[A-Za-z0-9_-]{20,}|$PHRASE|$INVITE|reset-password#token|activate#token|real\.student@|real\.instructor@|real\.import" "$S/backend.log" "$S/frontend.log" | awk -F: '{s+=$2} END {print s}')
echo "secrets in logs: $leaks"
[ "$leaks" = 0 ] || status=1
if [ "$status" != 0 ]; then
  # Enough context to diagnose a CI failure; this run's generated credentials are masked.
  for log in backend frontend; do
    echo "--- last lines of the $log log"
    tail -40 "$S/$log.log" | sed -e "s/$PHRASE/<passphrase>/g" -e "s/$INVITE/<invitation>/g" -E -e 's/eyJ[A-Za-z0-9_-]{20,}[A-Za-z0-9._-]*/<jwt>/g'
  done
fi
exit $status
