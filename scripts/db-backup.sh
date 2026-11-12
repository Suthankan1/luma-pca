#!/bin/bash
# Logical backup of a LUMA PostgreSQL database (pg_dump custom format) to backups/ (gitignored).
#
#   PGPASSWORD=… scripts/db-backup.sh <host> <port> <database> <user>
#
# The dump holds personal data and password hashes: keep it encrypted and access-controlled,
# never commit, email or paste it, and delete it when its retention period ends. Managed
# hosting (for example RDS automated backups with point-in-time recovery) is the primary
# backup in production; this is for migrations, exports to a new host and restore drills.
# Prove a dump can be restored with scripts/db-restore-drill.sh.
set -euo pipefail
[ $# -eq 4 ] || { echo "Usage: PGPASSWORD=… $0 <host> <port> <database> <user>"; exit 2; }
[ -n "${PGPASSWORD:-}" ] || { echo "Set PGPASSWORD in the environment (it is never printed)."; exit 2; }
HOST=$1; PORT=$2; DB=$3; USER_NAME=$4
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# From a container, this Mac is host.docker.internal.
case "$HOST" in localhost|127.0.0.1) HOST=host.docker.internal ;; esac
mkdir -p "$ROOT/backups"; chmod 700 "$ROOT/backups"
OUT="$ROOT/backups/luma-$DB-$(date -u +%Y%m%dT%H%M%SZ).dump"
umask 077
docker run --rm -i --add-host host.docker.internal:host-gateway -e PGPASSWORD postgres:17-alpine \
  pg_dump -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB" -Fc --no-owner > "$OUT"
echo "Backup written: $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes, sha256 $(shasum -a 256 "$OUT" | cut -c1-16)…)"
echo "It contains personal data: store it encrypted and delete it when no longer needed."
