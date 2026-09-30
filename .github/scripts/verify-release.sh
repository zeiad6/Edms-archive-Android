#!/usr/bin/env bash
#
# Gate: a release APK must be signed, and signed with the key this project owns.
#
# The build silently produced `app-release-unsigned.apk` for several runs because
# no signing material was configured — Gradle has no failure mode here, it just
# skips the signing step. That APK installs nowhere, which is exactly the class
# of bug that reaches a user as "the release build doesn't work". This turns it
# into a red build instead.
set -euo pipefail

APKS=(app/build/outputs/apk/release/*.apk)
if [ ! -e "${APKS[0]}" ]; then
  echo "::error::no release APK was produced"
  exit 1
fi

APK="${APKS[0]}"
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$APKSIGNER" ]; then
  echo "::error::apksigner not found under \$ANDROID_HOME/build-tools"
  exit 1
fi

echo "### Release signature" >> "$GITHUB_STEP_SUMMARY"
echo "APK: \`$(basename "$APK")\` · $(du -h "$APK" | cut -f1)" >> "$GITHUB_STEP_SUMMARY"

if ! "$APKSIGNER" verify --print-certs "$APK" > /tmp/certs.txt 2>&1; then
  {
    echo
    echo "**FAIL** — the release APK is not signed, so Android cannot install it:"
    echo '```'
    cat /tmp/certs.txt
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
  cat /tmp/certs.txt
  exit 1
fi

# The v2 scheme is what Android 7+ actually checks; v1 alone is not enough.
if ! "$APKSIGNER" verify --min-sdk-version 26 "$APK" >/dev/null 2>&1; then
  echo "::error::signature does not verify for minSdk 26"
  exit 1
fi

{
  echo
  echo "**PASS** — signed and installable."
  echo '```'
  sed -n '1,8p' /tmp/certs.txt
  echo '```'
} >> "$GITHUB_STEP_SUMMARY"

echo "release APK signature verified"
grep -E "^Signer #1 certificate (DN|SHA-256 digest)" /tmp/certs.txt || true
