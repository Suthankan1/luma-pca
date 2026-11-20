#!/bin/bash
# Generates production-grade persistent cryptographic keys for LUMA:
#   1. RS256 signing private key (PKCS#8 RSA 2048-bit) for access tokens
#   2. Public key in X.509 format (for JWKS verification / rotation)
#
# Usage:
#   scripts/generate-auth-keys.sh [--write-env] [--dir <directory>]
#
# Options:
#   --write-env    Directly injects the keys into .env (gitignored) and sets
#                  LUMA_AUTH_EPHEMERAL_SIGNING_KEY=false
#   --dir <path>   Directory to save raw PEM and KEY files (default: secrets/)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS_DIR="$ROOT/secrets"
WRITE_ENV=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --write-env)
      WRITE_ENV=true
      shift
      ;;
    --dir)
      SECRETS_DIR="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--write-env] [--dir <directory>]" >&2
      exit 1
      ;;
  esac
done

# Ensure output directory is gitignored before saving files
git -C "$ROOT" check-ignore -q "$SECRETS_DIR/" || {
  echo "Target directory $SECRETS_DIR is not gitignored; refusing to write keys." >&2
  exit 1
}

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"

PRIVATE_KEY_FILE="$SECRETS_DIR/auth-signing-key-pkcs8.pem"
PUBLIC_KEY_FILE="$SECRETS_DIR/auth-verification-key.pem"

echo "==> Generating RSA 2048-bit PKCS#8 private key for token signing..."
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY_FILE"
chmod 600 "$PRIVATE_KEY_FILE"

echo "==> Extracting public verification key..."
openssl rsa -pubout -in "$PRIVATE_KEY_FILE" -out "$PUBLIC_KEY_FILE"
chmod 644 "$PUBLIC_KEY_FILE"

# Format private key into a single line with escaped \n for environment variable storage
SIGNING_KEY_ESCAPED="$(awk '{printf "%s\\n", $0}' "$PRIVATE_KEY_FILE" | sed 's/\\n$//')"

echo "Keys generated successfully in $SECRETS_DIR:"
echo "  - $PRIVATE_KEY_FILE (mode 600)"
echo "  - $PUBLIC_KEY_FILE (mode 644)"

if [ "$WRITE_ENV" = true ]; then
  ENV_FILE="$ROOT/.env"
  git -C "$ROOT" check-ignore -q "$ENV_FILE" || {
    echo "$ENV_FILE is not gitignored; refusing to write keys." >&2
    exit 1
  }

  touch "$ENV_FILE"
  # Strip any existing definitions
  grep -vE "^(LUMA_AUTH_EPHEMERAL_SIGNING_KEY|LUMA_AUTH_SIGNING_KEY_PEM|LUMA_AUTH_MFA_ENCRYPTION_KEY)=" "$ENV_FILE" > "$ENV_FILE.tmp" || true

  {
    cat "$ENV_FILE.tmp"
    echo "LUMA_AUTH_EPHEMERAL_SIGNING_KEY=false"
    echo "LUMA_AUTH_SIGNING_KEY_PEM=\"$SIGNING_KEY_ESCAPED\""
  } > "$ENV_FILE"
  rm -f "$ENV_FILE.tmp"
  chmod 600 "$ENV_FILE"
  echo "==> Updated $ENV_FILE with persistent production keys (LUMA_AUTH_EPHEMERAL_SIGNING_KEY=false)."
fi
