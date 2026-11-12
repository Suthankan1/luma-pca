#!/bin/bash
# Writes .env.zoom-test for scripts/zoom-test-tunnel.sh. Secrets are typed or pasted at hidden
# prompts: they are not echoed, not passed as arguments (so not in shell history or the
# process list) and the file is created readable by you only. Re-run it to change values;
# press Return to keep a current value.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILE="$ROOT/.env.zoom-test"
git -C "$ROOT" check-ignore -q .env.zoom-test || { echo ".env.zoom-test is not gitignored; refusing to write secrets."; exit 1; }

current() { [ -f "$FILE" ] && grep -E "^$1=" "$FILE" | head -1 | cut -d= -f2- ; }
ask() { # name, prompt, hidden(yes/no)
  local value old; old=$(current "$1")
  if [ "$3" = yes ]; then read -r -s -p "$2${old:+ [set; Return keeps it]}: " value; echo
  else read -r -p "$2${old:+ [$old]}: " value; fi
  printf '%s' "${value:-$old}"
}

TOKEN=$(ask LUMA_ZOOM_WEBHOOK_SECRET_TOKEN "Zoom app Secret Token (Features page)" yes)
ALLOW=$(ask LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST "Test webinar ID(s), digits, comma-separated" no)
ACCOUNT=$(ask ZOOM_ACCOUNT_ID "Zoom Account ID (App Credentials; optional for now)" yes)
CLIENT=$(ask ZOOM_CLIENT_ID "Zoom Client ID (optional for now)" yes)
SECRET=$(ask ZOOM_CLIENT_SECRET "Zoom Client Secret (optional for now)" yes)

[ ${#TOKEN} -ge 16 ] || { echo "The Secret Token looks too short; nothing written."; exit 1; }
echo "$ALLOW" | grep -qE '^[0-9][0-9 ,-]{8,}$' || { echo "Give the test webinar ID (digits); nothing written."; exit 1; }

umask 077
{
  echo "# Written by scripts/zoom-test-setup.sh on $(date +%F). Gitignored: never commit or share."
  echo "LUMA_ZOOM_WEBHOOK_SECRET_TOKEN=$TOKEN"
  echo "LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST=$ALLOW"
  echo "ZOOM_ACCOUNT_ID=$ACCOUNT"
  echo "ZOOM_CLIENT_ID=$CLIENT"
  echo "ZOOM_CLIENT_SECRET=$SECRET"
} > "$FILE.tmp" && mv "$FILE.tmp" "$FILE" && chmod 600 "$FILE"
echo "Saved $FILE (readable by you only; gitignored). Values are not shown."
