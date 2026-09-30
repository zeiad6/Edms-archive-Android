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
printf '%s' "$KEYSTORE_B64" | base64 -d > "$STORE_FILE"
if ! keytool -list -keystore "$STORE_FILE" -storepass "$STORE_PASSWORD" >/dev/null 2>&1; then
  echo "::error::$STORE_FILE did not decode into a readable keystore"
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
