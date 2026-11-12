#!/bin/bash
# Restore drill: proves a LUMA backup restores completely, without touching the source.
#
#   PGPASSWORD=… scripts/db-restore-drill.sh <host> <port> <database> <user>
#
# 1. Takes a fresh pg_dump of the source (read-only; writes continue meanwhile).
# 2. Restores it into a throwaway PostgreSQL 17 container with no published port.
# 3. Compares source and copy: the Flyway history (versions, checksums, success) must be
#    identical, and every table's row count must match (rows written to the source after
#    the dump started can make a busy table differ; the report says which).
# 4. Checks the restored audit log is still append-only (an UPDATE must be refused).
# The dump and the container are deleted afterwards; nothing is printed but counts.
set -uo pipefail
[ $# -eq 4 ] || { echo "Usage: PGPASSWORD=… $0 <host> <port> <database> <user>"; exit 2; }
[ -n "${PGPASSWORD:-}" ] || { echo "Set PGPASSWORD in the environment (it is never printed)."; exit 2; }
HOST=$1; PORT=$2; DB=$3; USER_NAME=$4
case "$HOST" in localhost|127.0.0.1) HOST=host.docker.internal ;; esac
DRILL=luma-restore-drill-$$
S="$(mktemp -d)"; chmod 700 "$S"
cleanup() { docker rm -f $DRILL >/dev/null 2>&1; rm -rf "$S"; }
trap cleanup EXIT INT TERM
src() { docker run --rm -i --add-host host.docker.internal:host-gateway -e PGPASSWORD postgres:17-alpine \
  psql -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB" -At -v ON_ERROR_STOP=1 "$@"; }
copy() { docker exec -i $DRILL psql -U luma -d luma -At -v ON_ERROR_STOP=1 "$@"; }

echo "1. Dumping the source…"
umask 077
docker run --rm -i --add-host host.docker.internal:host-gateway -e PGPASSWORD postgres:17-alpine \
  pg_dump -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB" -Fc --no-owner > "$S/luma.dump" || { echo "pg_dump failed"; exit 1; }
echo "   $(wc -c < "$S/luma.dump" | tr -d ' ') bytes"

echo "2. Restoring into a throwaway database…"
docker run -d --rm --name $DRILL -e POSTGRES_USER=luma -e POSTGRES_PASSWORD="$(openssl rand -hex 16)" -e POSTGRES_DB=luma \
  postgres:17-alpine >/dev/null
for i in $(seq 1 60); do docker exec $DRILL pg_isready -U luma -d luma >/dev/null 2>&1 && break; sleep 1; done; sleep 2
docker exec -i $DRILL pg_restore -U luma -d luma --no-owner --exit-on-error < "$S/luma.dump" || { echo "pg_restore FAILED"; exit 1; }

echo "3. Comparing source and copy…"
HISTORY="SELECT md5(string_agg(coalesce(version,'') || ':' || coalesce(checksum::text,'') || ':' || success, ',' ORDER BY installed_rank)) || ' ' || count(*) || ' migrations, latest V' || (array_agg(version ORDER BY installed_rank DESC))[1] FROM flyway_schema_history"
SRC_HISTORY=$(src -c "$HISTORY"); COPY_HISTORY=$(copy -c "$HISTORY")
FAIL=0
if [ "$SRC_HISTORY" = "$COPY_HISTORY" ]; then echo "   migration history identical (${SRC_HISTORY#* })"; else echo "   MIGRATION HISTORY DIFFERS"; FAIL=1; fi
TABLES=$(copy -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")
DIFFER=0; COUNTED=0; ROWS=0
for t in $TABLES; do
  a=$(src -c "SELECT count(*) FROM \"$t\""); b=$(copy -c "SELECT count(*) FROM \"$t\"")
  COUNTED=$((COUNTED + 1)); ROWS=$((ROWS + b))
  [ "$a" = "$b" ] || { echo "   $t: source $a, copy $b"; DIFFER=$((DIFFER + 1)); }
done
[ $DIFFER -eq 0 ] && echo "   $COUNTED tables, $ROWS rows, all counts match" || { echo "   $DIFFER of $COUNTED tables differ"; FAIL=1; }

echo "4. Checking the restored audit log is append-only…"
TRIGGER=$(copy -c "SELECT count(*) FROM pg_trigger WHERE tgname = 'audit_events_no_update_or_delete' AND NOT tgisinternal")
AUDIT_ROWS=$(copy -c "SELECT count(*) FROM audit_events")
if [ "$TRIGGER" != 1 ]; then
  echo "   APPEND-ONLY TRIGGER MISSING"; FAIL=1
elif [ "$AUDIT_ROWS" -gt 0 ] && copy -c "UPDATE audit_events SET action = action" >/dev/null 2>&1; then
  echo "   AUDIT LOG ACCEPTED AN UPDATE"; FAIL=1
else
  echo "   trigger present$([ "$AUDIT_ROWS" -gt 0 ] && echo "; an update of the $AUDIT_ROWS events was refused")"
fi

[ $FAIL -eq 0 ] && echo "RESTORE DRILL PASSED" || echo "RESTORE DRILL FAILED"
exit $FAIL
