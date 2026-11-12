#!/bin/bash
# Temporary public webhook endpoint for a real Zoom test webinar (slice 003A), on this Mac.
#
#   scripts/zoom-test-tunnel.sh          # start; prints the webhook URL to give Zoom; Ctrl+C stops
#   scripts/zoom-test-tunnel.sh summary  # what has been stored so far
#
# What runs: PostgreSQL 17 (data kept in the Docker volume luma-zoom-test-data), the backend
# jar with webhook receipt and processing on, the web app for reviewing results, an nginx
# proxy that passes ONLY `POST /api/v1/zoom/webhooks` and answers 404 to everything else, and
# a Cloudflare quick tunnel (no account) giving that proxy a temporary https URL. The API and
# web app listen on this Mac only (127.0.0.1); nothing else is reachable from the internet.
#
# Settings come from .env.zoom-test in the repository root (gitignored; create it yourself,
# never commit or paste it anywhere):
#   LUMA_ZOOM_WEBHOOK_SECRET_TOKEN=<Secret Token from the Zoom app's Features page>
#   LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST=<test webinar ID, digits; comma-separated for several>
# The allow-list is required: the Zoom event subscription covers the whole account, and
# events from real classes must not be stored. Events for other webinars are acknowledged
# and dropped unread.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETTINGS="${ZOOM_TEST_SETTINGS:-$ROOT/.env.zoom-test}"
STATE="${SETTINGS}-state"   # generated: local database password (gitignored like the settings)
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="$(ls "$ROOT"/backend/target/luma-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
PG=luma-zoom-test-pg; PROXY=luma-zoom-test-proxy; TUNNEL=luma-zoom-test-tunnel; NET=luma-zoom-test
VOLUME=${ZOOM_TEST_VOLUME:-luma-zoom-test-data}; API=18083; WEB=3400; ORIGIN=http://127.0.0.1:$WEB; PROXY_PORT=18084
S="$(mktemp -d)"

psql_q() { docker exec -i $PG psql -At -q -U luma -d luma -v ON_ERROR_STOP=1 "$@"; }

summary() {
  echo "--- stored so far (webinars in the allow-list only)"
  psql_q -c "SELECT '  receipts: ' || coalesce(string_agg(processing_status || '=' || n, ' '), 'none') FROM (SELECT processing_status, count(*) n FROM zoom_webhook_receipts GROUP BY 1) t" \
    -c "SELECT '  events by type: ' || coalesce(string_agg(event_type || '=' || n, ' '), 'none') FROM (SELECT event_type, count(*) n FROM zoom_occurrence_events GROUP BY 1) t" \
    -c "SELECT '  occurrences: ' || count(DISTINCT occurrence_uuid) FROM zoom_occurrence_events"
}

if [ "${1:-}" = summary ]; then summary; exit; fi

[ -f "$SETTINGS" ] || { echo "Create $SETTINGS first (see the header of this script)."; exit 2; }
# Read only the two expected keys; never echo their values.
TOKEN=$(grep -E '^LUMA_ZOOM_WEBHOOK_SECRET_TOKEN=' "$SETTINGS" | head -1 | cut -d= -f2- | tr -d '\r"' | xargs)
ALLOW=$(grep -E '^LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST=' "$SETTINGS" | head -1 | cut -d= -f2- | tr -d '\r"' | xargs)
[ ${#TOKEN} -ge 16 ] || { echo "LUMA_ZOOM_WEBHOOK_SECRET_TOKEN is missing or too short in $SETTINGS."; exit 2; }
echo "$ALLOW" | grep -qE '^[0-9 ,-]{9,}$' || { echo "LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST must list the test webinar ID(s) in $SETTINGS."; exit 2; }
[ -f "$JAR" ] || { echo "Build the backend first: mvn -f backend/pom.xml package"; exit 2; }
[ -d "$ROOT/frontend/.next" ] || { echo "Build the frontend first: npm --prefix frontend run build"; exit 2; }
chmod 600 "$SETTINGS"
if [ ! -f "$STATE" ]; then printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 16)" > "$STATE"; chmod 600 "$STATE"; fi
DBPASS=$(grep '^DB_PASSWORD=' "$STATE" | cut -d= -f2)

cleanup() {
  [ -n "${CLEANED:-}" ] && return; CLEANED=1   # runs once, whether stopped by Ctrl+C, kill or exit
  [ -n "${BPID:-}" ] && kill $BPID 2>/dev/null; [ -n "${FPID:-}" ] && kill $FPID 2>/dev/null
  docker rm -f $TUNNEL $PROXY >/dev/null 2>&1
  echo; [ "${STARTED:-}" = 1 ] && summary
  docker rm -f $PG >/dev/null 2>&1; docker network rm $NET >/dev/null 2>&1; rm -rf "$S"
  echo "Stopped. The public URL no longer works; the database volume $VOLUME is kept."
}
trap cleanup EXIT INT TERM

docker rm -f $PG $PROXY $TUNNEL >/dev/null 2>&1
docker network create $NET >/dev/null 2>&1
docker run -d --rm --name $PG --network $NET -v $VOLUME:/var/lib/postgresql/data -e POSTGRES_USER=luma \
  -e POSTGRES_PASSWORD=$DBPASS -e POSTGRES_DB=luma -p 127.0.0.1:55435:5432 postgres:17-alpine >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
export DB_URL=jdbc:postgresql://localhost:55435/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true

# The academy and its owner (first run only; later runs find them). The owner signs in to
# review receipts and occurrences on the Zoom review page.
LUMA_BACKEND_JAR="$JAR" LUMA_BOOTSTRAP_WEB_ORIGIN=$ORIGIN "$ROOT/scripts/bootstrap-owner.sh" "Zoom Test Academy" \
  zoom.test.owner@example.invalid > "$S/bootstrap.out" 2>&1
grep -q "already has an active owner" "$S/bootstrap.out" || {
  grep -E "^(LUMA owner bootstrap|Send this link|http)" "$S/bootstrap.out" | sed 's/^/  /'
  echo "  (Open that link in your browser to choose the test owner's password; it is shown only here.)"
}
ACADEMY=$(psql_q -c "SELECT id FROM academies WHERE name = 'Zoom Test Academy'")
[ -n "$ACADEMY" ] || { echo "No test academy; owner bootstrap output:"; grep "LUMA owner bootstrap" "$S/bootstrap.out"; exit 1; }

SERVER_ADDRESS=127.0.0.1 SERVER_PORT=$API LUMA_ZOOM_WEBHOOK_SECRET_TOKEN="$TOKEN" LUMA_ZOOM_WEBHOOK_ACADEMY_ID=$ACADEMY \
  LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST="$ALLOW" LUMA_ZOOM_PROCESSING_ENABLED=true "$JAVA" -jar "$JAR" > "$S/backend.log" 2>&1 & BPID=$!
unset TOKEN
for i in $(seq 1 90); do curl -sf 127.0.0.1:$API/actuator/health/readiness >/dev/null && break; sleep 1; done
curl -sf 127.0.0.1:$API/actuator/health/readiness >/dev/null || { echo "backend did not start"; grep -E " ERROR |Caused by" "$S/backend.log" | head -5; exit 1; }
(cd "$ROOT/frontend" && LUMA_WEB_ORIGIN=$ORIGIN LUMA_SESSION_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=') \
  API_INTERNAL_URL=http://127.0.0.1:$API node node_modules/next/dist/bin/next start -H 127.0.0.1 -p $WEB > "$S/frontend.log" 2>&1) & FPID=$!

# Only the webhook path is forwarded; the backend sees the request from this Mac.
cat > "$S/nginx.conf" <<NGINX
events {}
http {
  access_log off;
  server_tokens off;
  server {
    listen 8080;
    client_max_body_size 64k;
    location = /api/v1/zoom/webhooks {
      limit_except POST { deny all; }
      proxy_pass http://host.docker.internal:$API;
      proxy_set_header X-Forwarded-For "";
    }
    location / { return 404; }
  }
}
NGINX
docker run -d --rm --name $PROXY --network $NET --add-host host.docker.internal:host-gateway \
  -v "$S/nginx.conf:/etc/nginx/nginx.conf:ro" -p 127.0.0.1:$PROXY_PORT:8080 nginx:1.27.2-alpine >/dev/null
# HTTP/2 over TCP 443: cloudflared's default QUIC (UDP) is often blocked and then never registers.
docker run -d --rm --name $TUNNEL --network $NET cloudflare/cloudflared:2025.11.1 tunnel --no-autoupdate \
  --protocol http2 --url http://$PROXY:8080 >/dev/null
URL=""
for i in $(seq 1 60); do
  URL=$(docker logs $TUNNEL 2>&1 | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | head -1)
  [ -n "$URL" ] && docker logs $TUNNEL 2>&1 | grep -q "Registered tunnel connection" && break; sleep 1
done
docker logs $TUNNEL 2>&1 | grep -q "Registered tunnel connection" || { echo "The tunnel did not start:"; docker logs $TUNNEL 2>&1 | tail -5; exit 1; }
STARTED=1
# A new quick-tunnel name can take a little while to resolve.
for i in $(seq 1 45); do [ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/")" = 404 ] && break; sleep 2; done
[ "$(curl -s -o /dev/null -w '%{http_code}' "$URL/")" = 404 ] || echo "  (warning: $URL is not answering yet; give it a minute)"

cat <<EOF

  Webhook endpoint URL for the Zoom app's event subscription:

      $URL/api/v1/zoom/webhooks

  Allowed webinar(s): $ALLOW    Academy: $ACADEMY
  Review results: $ORIGIN/staff/zoom (sign in as zoom.test.owner@example.invalid)
  Anything else at $URL answers 404. The URL changes every time this script starts,
  so update the Zoom app's endpoint (and validate it) after each restart.
  Press Ctrl+C to stop.
EOF
while kill -0 $BPID 2>/dev/null; do
  sleep 30
  grep -cE "Zoom webhook webinar\\.[a-z_]+ stored" "$S/backend.log" | xargs printf '  %s events stored so far\r'
done
echo "The backend stopped unexpectedly:"; grep -E " ERROR " "$S/backend.log" | tail -3
