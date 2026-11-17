#!/bin/bash
# Checks the Zoom Server-to-Server OAuth app against the real Zoom API (slice 003A):
#
#   scripts/zoom-api-check.sh [webinar ID]      # default: the first ID in the allow-list
#
# Reads ZOOM_ACCOUNT_ID, ZOOM_CLIENT_ID, ZOOM_CLIENT_SECRET and LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST
# from .env.zoom-test (gitignored; never echoed) and runs the backend jar without a web server,
# with a throwaway PostgreSQL. Prints the granted scopes, facts about the webinar and counts of
# registrants and join sessions: never names or emails. Only webinars on the allow-list are read.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETTINGS="${ZOOM_TEST_SETTINGS:-$ROOT/.env.zoom-test}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="$(ls "$ROOT"/backend/target/luma-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
PG=luma-zoom-api-check-pg
[ -f "$SETTINGS" ] || { echo "Create $SETTINGS first (scripts/zoom-test-setup.sh)."; exit 2; }
[ -f "$JAR" ] || { echo "Build the backend first: mvn -f backend/pom.xml package"; exit 2; }
value() { grep -E "^$1=" "$SETTINGS" | head -1 | cut -d= -f2- | tr -d '\r"' | xargs; }
ALLOW=$(value LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST)
WEBINAR=${1:-${ALLOW%%,*}}
S="$(mktemp -d)"
cleanup() { docker rm -f $PG >/dev/null 2>&1; rm -rf "$S"; }
trap cleanup EXIT
DBPASS=$(openssl rand -hex 12)
docker rm -f $PG >/dev/null 2>&1
docker run -d --rm --name $PG -e POSTGRES_USER=luma -e POSTGRES_PASSWORD=$DBPASS -e POSTGRES_DB=luma \
  -p 127.0.0.1:55436:5432 postgres:17-alpine >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
# Credentials go only into this process's environment.
LUMA_ZOOM_API_ACCOUNT_ID="$(value ZOOM_ACCOUNT_ID)" LUMA_ZOOM_API_CLIENT_ID="$(value ZOOM_CLIENT_ID)" \
LUMA_ZOOM_API_CLIENT_SECRET="$(value ZOOM_CLIENT_SECRET)" LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST="$ALLOW" \
DB_URL=jdbc:postgresql://localhost:55436/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true \
  "$JAVA" -jar "$JAR" --spring.main.web-application-type=none --spring.main.banner-mode=off \
  --luma.zoom.processing.enabled=false --luma.rate-limit.enabled=false "--luma.zoom-api-check.webinar-id=$WEBINAR" > "$S/out.log" 2>&1
code=$?
grep -E "^(Token|Webinar|Registrants|Past instances|Latest instance|Check-a-webinar view|Upcoming webinars|Zoom API check)" "$S/out.log"
[ $code = 0 ] || grep -E " ERROR |Caused by" "$S/out.log" | grep -v "Zoom API check" | head -3
exit $code
