#!/bin/bash
# Real-stack auth smoke test: throwaway PostgreSQL 17 + the built Spring backend +
# the Next.js dev server, driven with curl. Covers invitation activation, browser
# sign-in, the protected portal, refresh rotation, replay revocation and sign-out.
#
# Needs: Docker running, JDK 21 as `java` (or JAVA_HOME), Node 22+ as `node`,
# `mvn -f backend/pom.xml package` and `npm --prefix frontend ci` already run.
# Uses ports 18080, 3100 and 55432. All credentials are generated per run.
#
# JSON bodies are built with printf into variables on purpose: macOS ships bash 3.2,
# which brace-expands a literal {"a":..,"b":..} inside "$(...)" into two requests.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$(mktemp -d)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PG=luma-smoke-pg; API=18080; WEB=3100; ORIGIN=http://localhost:$WEB
DBPASS=$(openssl rand -hex 12); PHRASE="$(openssl rand -hex 4) river under bridge"; INVITE=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
fail=0; check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3] got [$2]"; fail=1; fi; }
cleanup() { [ -n "${BPID:-}" ] && kill $BPID 2>/dev/null; [ -n "${FPID:-}" ] && kill $FPID 2>/dev/null; pkill -f "next dev -p $WEB" 2>/dev/null; docker rm -f $PG >/dev/null 2>&1; rm -rf "$S"; }
trap cleanup EXIT
docker rm -f $PG >/dev/null 2>&1
docker run -d --rm --name $PG -e POSTGRES_USER=luma -e POSTGRES_PASSWORD=$DBPASS -e POSTGRES_DB=luma -p 127.0.0.1:55432:5432 postgres:17-alpine >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
DB_URL=jdbc:postgresql://localhost:55432/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS SERVER_PORT=$API LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true \
  "$JAVA" -jar $ROOT/backend/target/luma-backend-0.1.0-SNAPSHOT.jar > "$S/backend.log" 2>&1 & BPID=$!
for i in $(seq 1 90); do curl -sf localhost:$API/actuator/health/readiness >/dev/null && break; sleep 1; done
check "backend ready" "$(curl -s -o /dev/null -w '%{http_code}' localhost:$API/actuator/health/readiness)" 200
DIGEST=$(printf '%s' "$INVITE" | shasum -a 256 | cut -d' ' -f1)
docker exec -i $PG psql -q -U luma -d luma -v ON_ERROR_STOP=1 <<SQL
INSERT INTO academies (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'Smoke Academy');
INSERT INTO accounts (id, academy_id, email, status) VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'smoke.student@example.invalid', 'PENDING_ACTIVATION');
INSERT INTO account_roles (account_id, role) VALUES ('22222222-2222-2222-2222-222222222222', 'STUDENT');
INSERT INTO account_invitations (id, account_id, token_hash, created_at, expires_at) VALUES ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', '$DIGEST', NOW(), NOW() + INTERVAL '1 hour');
SQL
BODY=$(printf '{"token":"%s","password":"%s"}' "$INVITE" "$PHRASE")
check "activate invitation over HTTP" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$BODY" localhost:$API/api/v1/auth/activate)" 204
check "account active in database" "$(docker exec $PG psql -At -U luma -d luma -c 'SELECT status FROM accounts')" ACTIVE
cd $ROOT/frontend
LUMA_WEB_ORIGIN=$ORIGIN LUMA_SESSION_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=') API_INTERNAL_URL=http://localhost:$API \
  node node_modules/next/dist/bin/next dev -p $WEB > "$S/frontend.log" 2>&1 & FPID=$!
for i in $(seq 1 90); do curl -sf -o /dev/null $ORIGIN/sign-in && break; sleep 1; done
JAR="$S/cookies.txt"; rm -f "$JAR"
web() { curl -s -o "$S/body.html" -D "$S/headers.txt" -b "$JAR" -c "$JAR" -w '%{http_code}' "$@"; }
loc() { grep -i '^location:' "$S/headers.txt" | tr -d '\r' | sed -E 's/^[Ll]ocation: *//; s#^https?://[^/]+##'; }
check "student page redirects when signed out" "$(web $ORIGIN/student)" 307
check "cross-origin login refused" "$(web -X POST -H 'Origin: http://evil.example' --data-urlencode email=smoke.student@example.invalid --data-urlencode "password=$PHRASE" $ORIGIN/api/session/login)" 403
web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.student@example.invalid --data-urlencode password=wrong-passphrase-here --data-urlencode portal=student $ORIGIN/api/session/login >/dev/null
check "wrong password -> invalid" "$(loc)" "/sign-in?error=invalid&portal=student"
check "login" "$(web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.student@example.invalid --data-urlencode "password=$PHRASE" --data-urlencode portal=student $ORIGIN/api/session/login)" 303
check "login redirects to student portal" "$(loc)" "/student"
check "both cookies HttpOnly" "$(grep -c -i '^set-cookie: luma-.*HttpOnly' "$S/headers.txt")" 2
check "student page loads" "$(web $ORIGIN/student)" 200
check "student page content" "$(grep -c 'Student portal' "$S/body.html" | sed 's/[1-9][0-9]*/yes/')" yes
check "staff page redirects a student" "$(web $ORIGIN/staff; echo; loc)" "$(printf '307\n/student')"
R1=$(grep 'luma-refresh' "$JAR" | awk '{print $NF}')
check "refresh" "$(web -X POST -H "Origin: $ORIGIN" -H 'Content-Type: application/x-www-form-urlencoded' --data portal=student $ORIGIN/api/session/refresh)" 303
check "refresh redirects to student portal" "$(loc)" "/student"
R2=$(grep 'luma-refresh' "$JAR" | awk '{print $NF}')
check "refresh token rotated" "$([ -n "$R2" ] && [ "$R1" != "$R2" ] && echo yes)" yes
check "student page after refresh" "$(web $ORIGIN/student)" 200
check "old refresh token replay rejected by API" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$(printf '{"refreshToken":"%s"}' "$R1")" localhost:$API/api/v1/auth/refresh)" 401
check "replay revoked the browser session" "$(web $ORIGIN/student)" 307
web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.student@example.invalid --data-urlencode "password=$PHRASE" $ORIGIN/api/session/login >/dev/null
check "second login blocked while refresh cookie present" "$(loc)" "/sign-in"
check "logout" "$(web -X POST -H "Origin: $ORIGIN" $ORIGIN/api/session/logout)" 303
check "logout redirect" "$(loc)" "/sign-in?signedOut=1"
check "login again after logout" "$(web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.student@example.invalid --data-urlencode "password=$PHRASE" $ORIGIN/api/session/login; echo; loc)" "$(printf '303\n/student')"
check "logout ends session" "$(web -X POST -H "Origin: $ORIGIN" $ORIGIN/api/session/logout >/dev/null; web $ORIGIN/student)" 307
check "sessions in database (revoked/total)" "$(docker exec $PG psql -At -U luma -d luma -c "SELECT count(*) FILTER (WHERE revoked_at IS NOT NULL) || '/' || count(*) FROM refresh_sessions")" "2/2"
check "no token or passphrase in backend log" "$(grep -c -E "eyJ[A-Za-z0-9_-]{20,}|$PHRASE|$INVITE" "$S/backend.log")" 0
check "no token or passphrase in frontend log" "$(grep -c -E "eyJ[A-Za-z0-9_-]{20,}|$PHRASE" "$S/frontend.log")" 0
rm -f "$JAR" "$S/body.html" "$S/headers.txt"
if [ $fail = 0 ]; then echo "STACK SMOKE PASSED"; else echo "STACK SMOKE FAILED"; tail -5 "$S/frontend.log"; exit 1; fi
