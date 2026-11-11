#!/bin/bash
# Real-stack browser test for the staff screens: throwaway PostgreSQL 17 + the built
# Spring backend jar + a production build of Next.js, driven by Playwright
# (frontend/tests/real-stack). Only an academy and a pending administrator with a
# known invitation are seeded, plus Zoom occurrences and a failed receipt (no personal
# data) for the review screen; everything else happens through the browser.
#
# Needs: Docker running, JDK 21 as `java` (or JAVA_HOME), Node 22+, a built backend
# (`mvn -f backend/pom.xml package`), `npm --prefix frontend ci` and
# `npm --prefix frontend run build`. Uses ports 18081, 3200 and 55433.
# All credentials are generated per run.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$(mktemp -d)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PG=luma-staff-ui-pg; API=18081; WEB=3200; ORIGIN=http://127.0.0.1:$WEB
DBPASS=$(openssl rand -hex 12)
PHRASE="$(openssl rand -hex 4) river under bridge"
INVITE=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
cleanup() { [ -n "${BPID:-}" ] && kill $BPID 2>/dev/null; [ -n "${FPID:-}" ] && kill $FPID 2>/dev/null; docker rm -f $PG >/dev/null 2>&1; rm -rf "$S"; }
trap cleanup EXIT

docker rm -f $PG >/dev/null 2>&1
docker run -d --rm --name $PG -e POSTGRES_USER=luma -e POSTGRES_PASSWORD=$DBPASS -e POSTGRES_DB=luma -p 127.0.0.1:55433:5432 postgres:17-alpine >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
DB_URL=jdbc:postgresql://localhost:55433/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS SERVER_PORT=$API LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true \
  LUMA_ZOOM_PROCESSING_ENABLED=false "$JAVA" -jar "$ROOT/backend/target/luma-backend-0.1.0-SNAPSHOT.jar" > "$S/backend.log" 2>&1 & BPID=$!
for i in $(seq 1 90); do curl -sf localhost:$API/actuator/health/readiness >/dev/null && break; sleep 1; done
curl -sf localhost:$API/actuator/health/readiness >/dev/null || { echo "backend did not start"; tail -20 "$S/backend.log"; exit 1; }

DIGEST=$(printf '%s' "$INVITE" | shasum -a 256 | cut -d' ' -f1)
docker exec -i $PG psql -q -U luma -d luma -v ON_ERROR_STOP=1 <<SQL
INSERT INTO academies (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'Real Stack Academy');
INSERT INTO accounts (id, academy_id, email, status) VALUES ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'smoke.admin@example.invalid', 'PENDING_ACTIVATION');
INSERT INTO account_roles (account_id, role) VALUES ('44444444-4444-4444-4444-444444444444', 'ACADEMY_OWNER');
INSERT INTO account_invitations (id, account_id, token_hash, created_at, expires_at) VALUES ('55555555-5555-5555-5555-555555555555', '44444444-4444-4444-4444-444444444444', '$DIGEST', NOW(), NOW() + INTERVAL '1 hour');
-- Zoom data as the (disabled) worker would have left it, without personal data: an occurrence on the
-- webinar the test's class will use (13 Jan 2027 18:10 Colombo), one on an unknown webinar, and a
-- dead-lettered receipt. Webhook ingress and processing stay off for this run.
INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at, processing_status, attempts, processed_at) VALUES
  ('66666666-6666-6666-6666-000000000001', '11111111-1111-1111-1111-111111111111', 'webinar.started', 1799844600000, repeat('1', 64), '{}', NOW(), 'PROCESSED', 1, NOW()),
  ('66666666-6666-6666-6666-000000000002', '11111111-1111-1111-1111-111111111111', 'webinar.started', 1799812800000, repeat('2', 64), '{}', NOW(), 'PROCESSED', 1, NOW());
INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at, processing_status, attempts, last_error) VALUES
  ('66666666-6666-6666-6666-000000000003', '11111111-1111-1111-1111-111111111111', 'webinar.participant_left', 1799848800000, repeat('3', 64), '{}', NOW(), 'DEAD', 10, 'Seeded failure for the real-stack test');
INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, event_type, event_ts, created_at) VALUES
  ('77777777-7777-7777-7777-000000000001', '11111111-1111-1111-1111-111111111111', '66666666-6666-6666-6666-000000000001', '81234567890', 'realAuto/AAAAAAAAAAAA==', 'webinar.started', 1799844600000, NOW()),
  ('77777777-7777-7777-7777-000000000002', '11111111-1111-1111-1111-111111111111', '66666666-6666-6666-6666-000000000002', '55500011101', 'realManual+AAAAAAAAAA==', 'webinar.started', 1799812800000, NOW());
SQL

cd "$ROOT/frontend"
LUMA_WEB_ORIGIN=$ORIGIN LUMA_SESSION_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=') API_INTERNAL_URL=http://127.0.0.1:$API \
  node node_modules/next/dist/bin/next start -H 127.0.0.1 -p $WEB > "$S/frontend.log" 2>&1 & FPID=$!
for i in $(seq 1 60); do curl -sf -o /dev/null $ORIGIN/sign-in && break; sleep 1; done

LUMA_REAL_STACK_URL=$ORIGIN LUMA_REAL_ADMIN_INVITE=$INVITE LUMA_REAL_PASSPHRASE=$PHRASE \
  npx playwright test -c playwright.real-stack.config.ts
status=$?

echo "--- database after the run"
docker exec $PG psql -At -U luma -d luma -c "SELECT 'courses=' || count(*) FROM courses" -c "SELECT 'batches=' || count(*) FROM batches" \
  -c "SELECT 'students=' || count(*) FROM students" -c "SELECT 'enrollments=' || count(*) || ' withdrawn=' || count(withdrawn_on) FROM enrollments" \
  -c "SELECT 'active accounts=' || count(*) FROM accounts WHERE status = 'ACTIVE'" \
  -c "SELECT 'staff assignments=' || count(*) FROM staff_assignments" \
  -c "SELECT 'class sessions=' || string_agg(starts_at::text || ' to ' || ends_at::text || ' webinar ' || zoom_webinar_id, ';') FROM class_sessions" \
  -c "SELECT 'occurrence links=' || string_agg(occurrence_uuid || ':' || link_source || ':' || coalesce(class_session_id::text, 'not a class'), ',') FROM zoom_occurrence_links" \
  -c "SELECT 'receipts=' || string_agg(processing_status || ':' || attempts, ',' ORDER BY id) FROM zoom_webhook_receipts" \
  -c "SELECT 'audit: ' || string_agg(action, ',' ORDER BY occurred_at) FROM audit_events"
leaks=$(grep -c -E "eyJ[A-Za-z0-9_-]{20,}|$PHRASE|$INVITE" "$S/backend.log" "$S/frontend.log" | awk -F: '{s+=$2} END {print s}')
echo "secrets in logs: $leaks"
[ "$leaks" = 0 ] || status=1
exit $status
