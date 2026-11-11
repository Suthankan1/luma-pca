#!/bin/bash
# Real-stack auth smoke test: throwaway PostgreSQL 17 + the built Spring backend +
# the Next.js dev server, driven with curl. Covers invitation activation, browser
# sign-in, MFA enrollment, recovery codes, TOTP verification, recovery code sign-in,
# protected portals, refresh rotation, replay revocation and sign-out.
#
# Needs: Docker running, JDK 21 as `java` (or JAVA_HOME), Node 22+ as `node`,
# `mvn -f backend/pom.xml package` and `npm --prefix frontend ci` already run.
# Uses ports 18080, 3100 and 55432. All credentials are generated per run.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$(mktemp -d)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
PG=luma-smoke-pg; API=18080; WEB=3100; ORIGIN=http://localhost:$WEB
DBPASS=$(openssl rand -hex 12); PHRASE="$(openssl rand -hex 4) river under bridge"
INVITE_STUDENT=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
INVITE_ADMIN=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
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

STUDENT_DIGEST=$(printf '%s' "$INVITE_STUDENT" | shasum -a 256 | cut -d' ' -f1)
ADMIN_DIGEST=$(printf '%s' "$INVITE_ADMIN" | shasum -a 256 | cut -d' ' -f1)
docker exec -i $PG psql -q -U luma -d luma -v ON_ERROR_STOP=1 <<SQL
INSERT INTO academies (id, name) VALUES ('11111111-1111-1111-1111-111111111111', 'Smoke Academy');
INSERT INTO accounts (id, academy_id, email, status) VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'smoke.student@example.invalid', 'PENDING_ACTIVATION');
INSERT INTO account_roles (account_id, role) VALUES ('22222222-2222-2222-2222-222222222222', 'STUDENT');
INSERT INTO account_invitations (id, account_id, token_hash, created_at, expires_at) VALUES ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', '$STUDENT_DIGEST', NOW(), NOW() + INTERVAL '1 hour');

INSERT INTO accounts (id, academy_id, email, status) VALUES ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'smoke.admin@example.invalid', 'PENDING_ACTIVATION');
INSERT INTO account_roles (account_id, role) VALUES ('44444444-4444-4444-4444-444444444444', 'ACADEMY_ADMIN');
INSERT INTO account_invitations (id, account_id, token_hash, created_at, expires_at) VALUES ('55555555-5555-5555-5555-555555555555', '44444444-4444-4444-4444-444444444444', '$ADMIN_DIGEST', NOW(), NOW() + INTERVAL '1 hour');
SQL

BODY_STUDENT=$(printf '{"token":"%s","password":"%s"}' "$INVITE_STUDENT" "$PHRASE")
check "activate student invitation" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$BODY_STUDENT" localhost:$API/api/v1/auth/activate)" 204

BODY_ADMIN=$(printf '{"token":"%s","password":"%s"}' "$INVITE_ADMIN" "$PHRASE")
check "activate admin invitation" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$BODY_ADMIN" localhost:$API/api/v1/auth/activate)" 204

cd $ROOT/frontend
LUMA_WEB_ORIGIN=$ORIGIN LUMA_SESSION_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=') API_INTERNAL_URL=http://localhost:$API \
  node node_modules/next/dist/bin/next dev -p $WEB > "$S/frontend.log" 2>&1 & FPID=$!
for i in $(seq 1 90); do curl -sf -o /dev/null $ORIGIN/sign-in && break; sleep 1; done
JAR="$S/cookies.txt"; rm -f "$JAR"
web() { curl -s -o "$S/body.html" -D "$S/headers.txt" -b "$JAR" -c "$JAR" -w '%{http_code}' "$@"; }
loc() { grep -i '^location:' "$S/headers.txt" | tr -d '\r' | sed -E 's/^[Ll]ocation: *//; s#^https?://[^/]+##'; }

# --- Student Flow ---
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

# Refresh & replay tests
R1=$(grep 'luma-refresh' "$JAR" | awk '{print $NF}')
check "refresh" "$(web -X POST -H "Origin: $ORIGIN" -H 'Content-Type: application/x-www-form-urlencoded' --data portal=student $ORIGIN/api/session/refresh)" 303
check "refresh redirects to student portal" "$(loc)" "/student"
R2=$(grep 'luma-refresh' "$JAR" | awk '{print $NF}')
check "refresh token rotated" "$([ -n "$R2" ] && [ "$R1" != "$R2" ] && echo yes)" yes
check "student page after refresh" "$(web $ORIGIN/student)" 200
check "old refresh token replay rejected by API" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$(printf '{"refreshToken":"%s"}' "$R1")" localhost:$API/api/v1/auth/refresh)" 401
check "replay revoked the browser session" "$(web $ORIGIN/student)" 307
web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.student@example.invalid --data-urlencode "password=$PHRASE" $ORIGIN/api/session/login >/dev/null
check "logout" "$(web -X POST -H "Origin: $ORIGIN" $ORIGIN/api/session/logout)" 303
check "logout redirect" "$(loc)" "/sign-in?signedOut=1"

# --- Admin MFA Enrollment Flow ---
rm -f "$JAR"
check "admin login requires MFA" "$(web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.admin@example.invalid --data-urlencode "password=$PHRASE" --data-urlencode portal=staff $ORIGIN/api/session/login)" 303
check "admin login redirects to mfa" "$(loc)" "/sign-in/mfa"
check "mfa cookie present" "$([ -n "$(grep 'luma-mfa' "$JAR")" ] && echo yes)" yes
check "no session access cookie yet" "$([ -z "$(grep 'luma-access' "$JAR")" ] && echo yes)" yes
web $ORIGIN/staff >/dev/null
check "staff page redirects unverified admin" "$(loc)" "/sign-in?portal=staff"
check "mfa enrollment page loads" "$(web $ORIGIN/sign-in/mfa)" 200
check "qr code rendered" "$(grep -c '<svg' "$S/body.html" | sed 's/[1-9][0-9]*/yes/')" yes

# Wrong code rejection
web -X POST -H "Origin: $ORIGIN" --data-urlencode code=000000 $ORIGIN/api/session/mfa-verify >/dev/null
check "wrong code rejected" "$(loc)" "/sign-in/mfa?error=code"

# Cancel MFA
check "cancel mfa" "$(web -X POST -H "Origin: $ORIGIN" $ORIGIN/api/session/mfa-cancel)" 303
check "cancel redirects to sign-in" "$(loc)" "/sign-in"
check "mfa cookie cleared on cancel" "$([ -z "$(grep 'luma-mfa' "$JAR")" ] && echo yes)" yes

# Re-initiate MFA
web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.admin@example.invalid --data-urlencode "password=$PHRASE" --data-urlencode portal=staff $ORIGIN/api/session/login >/dev/null
web $ORIGIN/sign-in/mfa >/dev/null
SECRET=$(grep -o 'class="mfa-secret"[^>]*>[^<]*' "$S/body.html" | sed -E 's/.*>//; s/[[:space:]]//g')

CODE=$(node -e '
  const crypto = require("crypto");
  const secret = process.argv[1];
  function base32Decode(str) {
    const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    let bits = 0, value = 0, output = [];
    for (let c of str) {
      let idx = chars.indexOf(c);
      if (idx === -1) continue;
      value = (value << 5) | idx;
      bits += 5;
      if (bits >= 8) { output.push((value >>> (bits - 8)) & 255); bits -= 8; }
    }
    return Buffer.from(output);
  }
  const key = base32Decode(secret);
  const step = Math.floor(Date.now() / 30000);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(step));
  const hmac = crypto.createHmac("sha1", key).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const binCode = (hmac.readUInt32BE(offset) & 0x7fffffff) % 1000000;
  console.log(String(binCode).padStart(6, "0"));
' "$SECRET")

check "verify code" "$(web -X POST -H "Origin: $ORIGIN" --data-urlencode "code=$CODE" $ORIGIN/api/session/mfa-verify)" 303
check "verify redirects to recovery codes" "$(loc)" "/sign-in/mfa/recovery-codes"
check "recovery cookie present" "$([ -n "$(grep 'luma-recovery' "$JAR")" ] && echo yes)" yes
check "mfa cookie cleared after verify" "$([ -z "$(grep 'luma-mfa' "$JAR")" ] && echo yes)" yes

check "recovery codes page loads" "$(web $ORIGIN/sign-in/mfa/recovery-codes)" 200
FIRST_RECOVERY=$(grep -o '<code[^>]*>[^<]*' "$S/body.html" | head -1 | sed -E 's/.*>//')

# Acknowledge recovery codes
check "acknowledge recovery codes" "$(web -X POST -H "Origin: $ORIGIN" --data-urlencode confirmed=on $ORIGIN/api/session/mfa-recovery)" 303
check "acknowledge redirects to staff portal" "$(loc)" "/staff"
check "recovery cookie cleared" "$([ -z "$(grep 'luma-recovery' "$JAR")" ] && echo yes)" yes
check "staff portal loads for admin" "$(web $ORIGIN/staff)" 200
check "staff content present" "$(grep -c 'Staff portal' "$S/body.html" | sed 's/[1-9][0-9]*/yes/')" yes

# Logout admin
web -X POST -H "Origin: $ORIGIN" $ORIGIN/api/session/logout >/dev/null

# --- Enrolled Admin sign-in using Recovery Code ---
rm -f "$JAR"
web -X POST -H "Origin: $ORIGIN" --data-urlencode email=smoke.admin@example.invalid --data-urlencode "password=$PHRASE" --data-urlencode portal=staff $ORIGIN/api/session/login >/dev/null
web $ORIGIN/sign-in/mfa >/dev/null
check "enrolled mfa page shows enter code" "$(grep -c 'Enter your code' "$S/body.html" | sed 's/[1-9][0-9]*/yes/')" yes
check "no qr code on enrolled sign-in" "$(grep -c '<svg' "$S/body.html")" 0

# Verify with recovery code
check "verify with recovery code" "$(web -X POST -H "Origin: $ORIGIN" --data-urlencode "code=$FIRST_RECOVERY" $ORIGIN/api/session/mfa-verify)" 303
check "recovery login redirects directly to staff" "$(loc)" "/staff"
check "staff portal loads after recovery login" "$(web $ORIGIN/staff)" 200

# Final logout
web -X POST -H "Origin: $ORIGIN" $ORIGIN/api/session/logout >/dev/null

check "no token or passphrase in backend log" "$(grep -c -E "eyJ[A-Za-z0-9_-]{20,}|$PHRASE|$INVITE_STUDENT|$INVITE_ADMIN" "$S/backend.log")" 0
check "no token or passphrase in frontend log" "$(grep -c -E "eyJ[A-Za-z0-9_-]{20,}|$PHRASE" "$S/frontend.log")" 0

rm -f "$JAR" "$S/body.html" "$S/headers.txt"
if [ $fail = 0 ]; then echo "STACK SMOKE PASSED"; else echo "STACK SMOKE FAILED"; tail -10 "$S/frontend.log"; exit 1; fi
