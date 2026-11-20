#!/bin/bash
# Adds a staff member to an academy (creating the academy the first time) and issues their activation invitation.
# There is no staff page: this is how every staff account is added (blocking: docs/delivery/runbooks/STAFF-ACCOUNTS.md).
#
#   scripts/bootstrap-owner.sh "Physics Cube Academy" person@academy.example [PRINT|EMAIL]
#
# Run it once per staff member, with the same environment as the API (DB_URL, DB_USERNAME,
# DB_PASSWORD, the signing key, and for EMAIL the LUMA_MAIL_* and SPRING_MAIL_*
# settings). It starts the backend jar without a web server, prints the outcome and exits:
# 0 done, 1 refused (for example the address is already an active staff member), 2 bad arguments,
# 3 saved but the email failed. Running it again before they activate sends a new
# invitation and cancels the old one; it never takes over an active or blocked account.
#
# PRINT (default) writes the link to this terminal only, never to the log. It is a
# credential for 72 hours: run this where the output is not collected, and send the link
# privately. Set LUMA_BOOTSTRAP_WEB_ORIGIN (or LUMA_MAIL_WEB_ORIGIN) so the full link is shown.
set -u
if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "usage: $0 \"Academy name\" person@example.com [PRINT|EMAIL]" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${LUMA_BACKEND_JAR:-$(ls "$ROOT"/backend/target/luma-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -1)}"
[ -f "$JAR" ] || { echo "Backend jar not found; build it with: mvn -f backend/pom.xml package" >&2; exit 2; }
# Arguments are passed as separate words, so names with spaces or quotes need no escaping.
exec "$JAVA" -jar "$JAR" \
  --spring.main.web-application-type=none \
  --spring.main.banner-mode=off \
  --luma.zoom.processing.enabled=false \
  --luma.rate-limit.enabled=false \
  "--luma.bootstrap-owner.academy-name=$1" \
  "--luma.bootstrap-owner.email=$2" \
  "--luma.bootstrap-owner.delivery=${3:-PRINT}" \
  "--luma.bootstrap-owner.web-origin=${LUMA_BOOTSTRAP_WEB_ORIGIN:-}"
