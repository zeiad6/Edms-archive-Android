#!/usr/bin/env bash
#
# Materialise the release signing key from the GitHub Actions secret.
#
# The key exists in exactly two places: this runner (for the length of one job)
# and the repository secret store. It is never in the repository, never in a
# workflow file, and the runner's copy is deleted when the job ends.
#
# Produces the untracked `keystore.properties` that app/build.gradle.kts reads
# when the EDMS_* environment variables are absent. `set -e` on purpose: a build
# that silently produces an *unsigned* APK is worse than a build that fails,
# because the unsigned APK looks fine until someone tries to install it.
set -euo pipefail

KEYSTORE_B64="${EDMS_KEYSTORE_B64:-}"
STORE_PASSWORD="${EDMS_STORE_PASSWORD:-}"
KEY_ALIAS="${EDMS_KEY_ALIAS:-}"
KEY_PASSWORD="${EDMS_KEY_PASSWORD:-}"

missing=()
[ -n "$KEYSTORE_B64" ] || missing+=(EDMS_KEYSTORE_B64)
[ -n "$STORE_PASSWORD" ] || missing+=(EDMS_STORE_PASSWORD)
[ -n "$KEY_ALIAS" ] || missing+=(EDMS_KEY_ALIAS)
[ -n "$KEY_PASSWORD" ] || missing+=(EDMS_KEY_PASSWORD)

if [ ${#missing[@]} -gt 0 ]; then
  echo "::error::missing signing secret(s): ${missing[*]}"
  echo "An unsigned release APK cannot be installed and cannot update an existing"
  echo "install, so this build is stopping instead of producing one."
  exit 1
fi

STORE_FILE=edms-release.jks

# Strip the base64 wrapper, then confirm it is really a keystore rather than
# trusting the round trip.
#
# `-i` plus an explicit whitespace strip matters: a secret that picked up a CR or
# a stray space in transit makes plain `base64 -d` abort with "invalid input",
# which is exactly the failure this script was written to replace. The
# keystore check below is the real gate — it cannot be satisfied by a corrupted
# or truncated file.
CLEAN_B64=$(printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]')
printf '%s' "$CLEAN_B64" | base64 -d -i > "$STORE_FILE" 2>/dev/null || true

if ! keytool -list -keystore "$STORE_FILE" -storepass "$STORE_PASSWORD" >/dev/null 2>&1; then
  echo "::error::the release key did not decode into a readable keystore"
  echo "received ${#KEYSTORE_B64} chars (${#CLEAN_B64} after whitespace strip); expected a PKCS12 keystore"
  echo "If the lengths differ, the secret was truncated — re-set EDMS_KEYSTORE_B64 with:"
  echo "  base64 -w0 edms-release.jks | gh secret set EDMS_KEYSTORE_B64 --repo \$REPO"
  rm -f "$STORE_FILE"
  exit 1
fi
chmod 600 "$STORE_FILE"

cat > keystore.properties <<EOF
storeFile=$STORE_FILE
storePassword=$STORE_PASSWORD
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASSWORD
EOF
chmod 600 keystore.properties

echo "release key material staged ($(du -h "$STORE_FILE" | cut -f1))"
