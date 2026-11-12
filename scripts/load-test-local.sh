#!/bin/bash
# Local load-test run (docs/testing/LOAD-TEST-PLAN.md): throwaway PostgreSQL 17, the backend
# jar and a production Next.js build on this machine, synthetic data for STUDENTS students,
# and the k6 scenarios in Docker (grafana/k6:1.4.2).
#
#   STUDENTS=150 SCENARIOS="sign-in webhooks staff" scripts/load-test-local.sh
#
# A LOCAL BASELINE ONLY: load generator, servers and database share one machine, so the
# numbers are never evidence of capacity. Use it to check the scripts and find gross
# bottlenecks; the capacity gate runs in a production-like environment.
#
# Needs Docker, JDK 21 (JAVA_HOME), Node 22, `mvn -f backend/pom.xml package` and
# `npm --prefix frontend ci && npm --prefix frontend run build`. Ports 18082, 3300, 55434.
# Every credential is generated per run; all data is synthetic (example.invalid).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
S="$(mktemp -d)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="$(ls "$ROOT"/backend/target/luma-backend-*.jar | grep -v '\.original$' | head -1)"
K6_IMAGE=grafana/k6:1.4.2
PG=luma-load-pg; API=18082; WEB=3300; ORIGIN=http://127.0.0.1:$WEB
STUDENTS=${STUDENTS:-150}
SCENARIOS=${SCENARIOS:-"sign-in webhooks staff"}
# Time windows scale with the class size unless set: the plan's 1,500 students use 600/180/120/120 s.
WINDOW_S=${WINDOW_S:-$(( STUDENTS * 600 / 1500 > 30 ? STUDENTS * 600 / 1500 : 30 ))}
JOIN_S=${JOIN_S:-$(( STUDENTS * 180 / 1500 > 20 ? STUDENTS * 180 / 1500 : 20 ))}
CLASS_S=${CLASS_S:-$(( STUDENTS * 120 / 1500 > 20 ? STUDENTS * 120 / 1500 : 20 ))}
LEAVE_S=${LEAVE_S:-$(( STUDENTS * 120 / 1500 > 20 ? STUDENTS * 120 / 1500 : 20 ))}
STAFF=${STAFF:-20}
DBPASS=$(openssl rand -hex 12)
PASSWORD="${LOAD_PASSWORD:-$(openssl rand -hex 4) lantern over harbour}"
WEBHOOK_SECRET=$(openssl rand -hex 24)
OCCURRENCE="load$(openssl rand -hex 8)=="
PERIOD_START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
cleanup() { [ -n "${STATS_PID:-}" ] && kill $STATS_PID 2>/dev/null; [ -n "${BPID:-}" ] && kill $BPID 2>/dev/null
  [ -n "${FPID:-}" ] && kill $FPID 2>/dev/null; docker rm -f $PG >/dev/null 2>&1; rm -rf "$S"; }
trap cleanup EXIT

# k6 in Docker reaches this machine's loopback through host.docker.internal on macOS
# (Docker Desktop) and directly with host networking on Linux. It always sends the web
# app's real origin in the Origin header, as a browser on this machine would.
if [ "$(uname)" = Darwin ]; then K6_NET=(); TARGET=host.docker.internal; else K6_NET=(--network host); TARGET=127.0.0.1; fi

docker rm -f $PG >/dev/null 2>&1
docker run -d --rm --name $PG -e POSTGRES_USER=luma -e POSTGRES_PASSWORD=$DBPASS -e POSTGRES_DB=luma \
  -p 127.0.0.1:55434:5432 postgres:17-alpine -c max_connections=200 \
  -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=top >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
psql_q() { docker exec -i $PG psql -At -q -U luma -d luma -v ON_ERROR_STOP=1 "$@"; }
export DB_URL=jdbc:postgresql://localhost:55434/luma DB_USERNAME=luma DB_PASSWORD=$DBPASS LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true

# The academy and its owner come from the operator command (which also applies the migrations).
LUMA_BACKEND_JAR="$JAR" LUMA_BOOTSTRAP_WEB_ORIGIN=$ORIGIN "$ROOT/scripts/bootstrap-owner.sh" "Load Test Academy" load.owner@example.invalid \
  > "$S/bootstrap.out" 2>&1 || { echo "owner bootstrap failed"; grep "LUMA owner bootstrap" "$S/bootstrap.out"; exit 1; }
INVITE=$(grep -oE "activate#token=[A-Za-z0-9_-]{43}" "$S/bootstrap.out" | head -1 | cut -d= -f2)
ACADEMY=$(psql_q -c "SELECT id FROM academies WHERE name = 'Load Test Academy'")
psql_q -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null

# ACCESS_TTL (e.g. PT1M) shortens access tokens so a short run exercises session refresh.
# DB_POOL sets the API's database connection pool (Spring's default is 10). The access log
# records method, path, status and milliseconds only: no query strings or client addresses.
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=${DB_POOL:-10} SERVER_TOMCAT_ACCESSLOG_ENABLED=true SERVER_TOMCAT_ACCESSLOG_DIRECTORY="$S" \
  SERVER_TOMCAT_ACCESSLOG_PREFIX=api-access SERVER_TOMCAT_ACCESSLOG_SUFFIX=.log SERVER_TOMCAT_ACCESSLOG_BUFFERED=false \
  SERVER_TOMCAT_ACCESSLOG_PATTERN='%m %U %s %{ms}T' \
SERVER_PORT=$API LUMA_AUTH_ACCESS_TOKEN_TTL=${ACCESS_TTL:-PT10M} LUMA_ZOOM_WEBHOOK_SECRET_TOKEN=$WEBHOOK_SECRET LUMA_ZOOM_WEBHOOK_ACADEMY_ID=$ACADEMY \
  "$JAVA" ${API_JAVA_OPTS:-} -jar "$JAR" > "$S/backend.log" 2>&1 & BPID=$!
for i in $(seq 1 90); do curl -sf localhost:$API/actuator/health/readiness >/dev/null && break; sleep 1; done
curl -sf localhost:$API/actuator/health/readiness >/dev/null || { echo "backend did not start"; tail -20 "$S/backend.log"; exit 1; }

# One real activation computes an Argon2id hash for the shared test password.
printf '{"token":"%s","password":"%s"}' "$INVITE" "$PASSWORD" > "$S/activate.json"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data @"$S/activate.json" localhost:$API/api/v1/auth/activate)" = 204 ] \
  || { echo "owner activation failed"; exit 1; }
rm -f "$S/activate.json"

echo "--- seeding $STUDENTS students, 3 batches, $STAFF instructors, one class"
psql_q <<SQL
CREATE TEMP TABLE seed AS SELECT password_hash AS hash FROM accounts WHERE email = 'load.owner@example.invalid';
INSERT INTO courses (id, academy_id, code, title) VALUES (gen_random_uuid(), '$ACADEMY', 'LOAD', 'Load test course');
INSERT INTO batches (id, academy_id, course_id, code, name, starts_on)
  SELECT gen_random_uuid(), '$ACADEMY', (SELECT id FROM courses WHERE code = 'LOAD'), b, 'Batch ' || b, DATE '2027-01-01'
  FROM unnest(ARRAY['A', 'B', 'C']) b;
INSERT INTO accounts (id, academy_id, email, password_hash, status, password_changed_at)
  SELECT gen_random_uuid(), '$ACADEMY', 'load.student' || g || '@example.invalid', (SELECT hash FROM seed), 'ACTIVE', now()
  FROM generate_series(1, $STUDENTS) g;
INSERT INTO account_roles (account_id, role) SELECT id, 'STUDENT' FROM accounts WHERE email LIKE 'load.student%';
INSERT INTO students (id, academy_id, account_id, student_number, full_name)
  SELECT gen_random_uuid(), '$ACADEMY', a.id, 'LOAD-' || lpad(substring(a.email from 'student([0-9]+)@'), 5, '0'),
         'Load Student ' || substring(a.email from 'student([0-9]+)@')
  FROM accounts a WHERE a.email LIKE 'load.student%';
INSERT INTO enrollments (id, academy_id, batch_id, student_id, enrolled_on)
  SELECT gen_random_uuid(), '$ACADEMY',
         (SELECT id FROM batches WHERE code = chr(65 + (substring(s.student_number from '[0-9]+')::int % 3))), s.id, DATE '2027-01-01'
  FROM students s;
INSERT INTO accounts (id, academy_id, email, password_hash, status, password_changed_at)
  SELECT gen_random_uuid(), '$ACADEMY', 'load.instructor' || g || '@example.invalid', (SELECT hash FROM seed), 'ACTIVE', now()
  FROM generate_series(1, $STAFF) g;
INSERT INTO account_roles (account_id, role) SELECT id, 'INSTRUCTOR' FROM accounts WHERE email LIKE 'load.instructor%';
INSERT INTO staff_assignments (id, academy_id, account_id, batch_id, role)
  SELECT gen_random_uuid(), '$ACADEMY', a.id, b.id, 'INSTRUCTOR' FROM accounts a CROSS JOIN batches b WHERE a.email LIKE 'load.instructor%';
INSERT INTO class_sessions (id, academy_id, title, starts_at, ends_at, time_zone, zoom_webinar_id)
  VALUES (gen_random_uuid(), '$ACADEMY', 'Load test class', TIMESTAMPTZ '2027-01-13 12:30Z', TIMESTAMPTZ '2027-01-13 15:00Z', 'Asia/Colombo', '81234567890');
INSERT INTO session_batches (session_id, batch_id, academy_id) SELECT (SELECT id FROM class_sessions), id, '$ACADEMY' FROM batches;
SQL
BATCH_IDS=$(psql_q -c "SELECT string_agg(id::text, ',') FROM batches")
SESSION_ID=$(psql_q -c "SELECT id FROM class_sessions")
echo "students=$(psql_q -c 'SELECT count(*) FROM students') enrollments=$(psql_q -c 'SELECT count(*) FROM enrollments')"

cd "$ROOT/frontend"
LUMA_TRUSTED_PROXY_HOPS=1 LUMA_WEB_ORIGIN=$ORIGIN LUMA_SESSION_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=') \
  API_INTERNAL_URL=http://127.0.0.1:$API node node_modules/next/dist/bin/next start -H 127.0.0.1 -p $WEB > "$S/frontend.log" 2>&1 & FPID=$!
for i in $(seq 1 60); do curl -sf -o /dev/null $ORIGIN/sign-in && break; sleep 1; done
JAVA_PID=$BPID
# CPU seconds used so far by a process and its children (Linux /proc, or macOS ps), and by the database container.
proc_cpu() {
  local total=0 p
  for p in $1 $(pgrep -P "$1" 2>/dev/null); do
    if [ -r /proc/$p/stat ]; then
      total=$(awk -v t="$(getconf CLK_TCK)" -v acc="$total" '{ sub(/^.*\) /, ""); print acc + ($12 + $13) / t }' /proc/$p/stat)
    else
      total=$(ps -o time= -p "$p" | awk -v acc="$total" '{ gsub(/-/, ":"); n = split($1, a, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + a[i]; print acc + s }')
    fi
  done
  echo "$total"
}
db_cpu() { docker exec $PG cat /sys/fs/cgroup/cpu.stat 2>/dev/null | awk '/^usage_usec/ { print $2 / 1e6 }'; }
# Resource use every 5 s: API and web process CPU/RSS, database container, busy database connections.
( while true; do
    printf '%s api %s web %s db %s dbconn %s\n' "$(date +%T)" "$(ps -o %cpu=,rss= -p $JAVA_PID | awk '{printf "cpu=%s%% rss=%dMB", $1, $2/1024}')" \
      "$(ps -o %cpu=,rss= -p $FPID | awk '{printf "cpu=%s%% rss=%dMB", $1, $2/1024}')" \
      "$(docker stats --no-stream --format 'cpu={{.CPUPerc}} mem={{.MemUsage}}' $PG 2>/dev/null)" \
      "$(psql_q -c "SELECT 'active=' || count(*) FILTER (WHERE state = 'active') || ' open=' || count(*) FROM pg_stat_activity WHERE application_name LIKE 'PostgreSQL JDBC%'" 2>/dev/null)"
    sleep 5; done ) > "$S/resources.log" 2>&1 & STATS_PID=$!
disown $STATS_PID

k6() { # name, script, env...
  local name=$1 script=$2; shift 2
  docker run --rm ${K6_NET[@]+"${K6_NET[@]}"} -v "$ROOT/load-tests/k6:/scripts:ro" -e K6_NO_USAGE_REPORT=true "$@" \
    $K6_IMAGE run --quiet "/scripts/$script" > "$S/k6-$name.txt" 2>&1
  echo "k6 $name exit $?" >> "$S/k6-exits.txt"
}
WEB_ENV=(-e BASE_URL=http://$TARGET:$WEB -e WEB_ORIGIN=$ORIGIN -e PASSWORD="$PASSWORD")
psql_q -c "SELECT pg_stat_statements_reset()" >/dev/null
: > "$S"/api-access*.log 2>/dev/null
CPU0_API=$(proc_cpu $JAVA_PID); CPU0_WEB=$(proc_cpu $FPID); CPU0_DB=$(db_cpu); T0=$(date +%s)
K6_PIDS=()
echo "--- running: $SCENARIOS (students=$STUDENTS window=${WINDOW_S}s joins=${JOIN_S}s)"
for scenario in $SCENARIOS; do
  case $scenario in
    sign-in) k6 sign-in sign-in-burst.js "${WEB_ENV[@]}" -e STUDENTS=$STUDENTS -e WINDOW_S=$WINDOW_S -e PER_ADDRESS=${PER_ADDRESS:-1} \
               -e PORTAL_VIEWS=${PORTAL_VIEWS:-3} -e THINK_S=${THINK_S:-10} & K6_PIDS+=($!) ;;
    webhooks) k6 webhooks webhook-burst.js -e API_URL=http://$TARGET:$API -e WEBHOOK_SECRET=$WEBHOOK_SECRET -e STUDENTS=$STUDENTS \
               -e JOIN_S=$JOIN_S -e CLASS_S=$CLASS_S -e LEAVE_S=$LEAVE_S -e OCCURRENCE=$OCCURRENCE & K6_PIDS+=($!) ;;
    staff) k6 staff staff-reads.js "${WEB_ENV[@]}" -e STAFF=$STAFF \
               -e DURATION_S=${STAFF_DURATION_S:-$(( WINDOW_S > JOIN_S + CLASS_S + LEAVE_S ? WINDOW_S : JOIN_S + CLASS_S + LEAVE_S ))} \
               -e BATCH_IDS=$BATCH_IDS -e SESSION_ID=$SESSION_ID & K6_PIDS+=($!) ;;
    *) echo "unknown scenario $scenario"; exit 2 ;;
  esac
done
wait "${K6_PIDS[@]}"
BURST_END=$(date +%s)
CPU1_API=$(proc_cpu $JAVA_PID); CPU1_WEB=$(proc_cpu $FPID); CPU1_DB=$(db_cpu)

status=0
if [[ " $SCENARIOS " == *" webhooks "* ]]; then
  # How long the worker needs after the last event to process the backlog.
  for i in $(seq 1 600); do
    [ "$(psql_q -c "SELECT count(*) FROM zoom_webhook_receipts WHERE processing_status = 'RECEIVED'")" = 0 ] && break; sleep 1
  done
  echo "--- webhooks: drained $(( $(date +%s) - BURST_END )) s after the last event;" \
    "$(psql_q -c "SELECT string_agg(processing_status || '=' || n, ' ') FROM (SELECT processing_status, count(*) n FROM zoom_webhook_receipts GROUP BY 1) t")," \
    "occurrence events=$(psql_q -c "SELECT count(*) FROM zoom_occurrence_events")"
  expected=$(( STUDENTS * 2 + (STUDENTS * 20 + 50) / 100 * 2 ))
  echo "    expected about $expected receipts (joins + leaves + 20% reconnect pairs)"
fi

for file in "$S"/k6-*.txt; do
  [ "$(basename "$file")" = k6-exits.txt ] && continue
  echo "=== $(basename "$file" .txt)"
  grep -E "✓|✗|THRESHOLDS|http_req_duration|http_req_failed|checks|iterations|  [{]|p\(9|ERRO" "$file" | grep -v "^\s*$" | head -40
  # Failure details logged by the scripts, most frequent first.
  grep -o 'msg="[a-z-]*: status [^"]*"' "$file" | sort | uniq -c | sort -rn | head -8
done
cat "$S/k6-exits.txt" 2>/dev/null || { echo "no k6 run finished"; status=1; }
grep -q "exit [^0]" "$S/k6-exits.txt" 2>/dev/null && status=1
delta() { awk -v a="${1:-0}" -v b="${2:-0}" 'BEGIN { printf "%.1f", b - a }'; }
echo "--- CPU seconds used during the scenarios ($(( BURST_END - T0 )) s):" \
  "api $(delta "$CPU0_API" "$CPU1_API") web $(delta "$CPU0_WEB" "$CPU1_WEB") db $(delta "$CPU0_DB" "$CPU1_DB")"
echo "--- API time per route (server side, from the access log): count p50 p95 max ms"
cat "$S"/api-access*.log 2>/dev/null | python3 -c '
import re, sys, collections
routes = collections.defaultdict(list)
for line in sys.stdin:
    parts = line.split()
    if len(parts) != 4: continue
    method, path, status, ms = parts
    path = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}", path)
    routes[method + " " + path].append(float(ms))
for route, times in sorted(routes.items(), key=lambda r: -sum(r[1]))[:10]:
    times.sort(); n = len(times)
    print(f"  {route:55s} {n:6d} {times[n // 2]:7.0f} {times[min(n - 1, int(n * 0.95))]:7.0f} {times[-1]:7.0f}")'
echo "--- database: busiest statements (calls, mean ms, total s)"
psql_q -F ' | ' -c "SELECT calls, round(mean_exec_time::numeric, 2), round((total_exec_time / 1000)::numeric, 1), left(regexp_replace(query, '\s+', ' ', 'g'), 90)
  FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat%' ORDER BY total_exec_time DESC LIMIT 6" | sed 's/^/  /'
echo "--- database connections: most busy at once $(grep -o 'active=[0-9]*' "$S/resources.log" | cut -d= -f2 | sort -n | tail -1) of a pool of ${DB_POOL:-10}"
echo "--- peak resources (from 5-second samples)"
awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^cpu=/) { split($i, a, "="); v = a[2] + 0; k = $(i-1); if (v > max[k]) max[k] = v } }
     END { for (k in max) printf "  %s peak cpu %s%%\n", k, max[k] }' "$S/resources.log"
tail -1 "$S/resources.log"
echo "--- backend log: $(grep -c ' ERROR ' "$S/backend.log") ERROR, $(grep -c ' WARN ' "$S/backend.log") WARN lines"
grep -E " ERROR " "$S/backend.log" | cut -c1-200 | sort | uniq -c | sort -rn | head -5
# Next.js logs server errors without a level; show the distinct messages, credentials masked.
echo "--- web log: $(grep -ciE "error|exception|timeout" "$S/frontend.log") error-like lines"
grep -iE "error|exception|timeout" "$S/frontend.log" | sed -E -e "s/$PASSWORD/<password>/g" -e 's/eyJ[A-Za-z0-9_.-]{20,}/<jwt>/g' \
  | cut -c1-200 | sort | uniq -c | sort -rn | head -8
echo "--- started $PERIOD_START; local baseline only, not capacity evidence"
# Debugging: keep the stack up (web $ORIGIN, API port $API) for KEEP_UP_S seconds.
[ -n "${KEEP_UP_S:-}" ] && { echo "keeping the stack up for $KEEP_UP_S s"; sleep "$KEEP_UP_S"; }
exit $status
