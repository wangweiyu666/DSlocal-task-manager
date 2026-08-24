#!/usr/bin/env bash
set -euo pipefail

manifest="$(find app/build/intermediates/merged_manifests -path '*offline*Release*' -name AndroidManifest.xml | head -n 1)"
test -n "$manifest"
if grep -Eq 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)' "$manifest"; then
  echo "offline Android manifest contains a network permission" >&2
  exit 1
fi

./gradlew :app:dependencies --configuration offlineReleaseRuntimeClasspath --no-daemon > app/build/offline-dependencies.txt
if grep -Eiq '(^|[^a-z])(okhttp|retrofit|ktor-client)([^a-z]|$)' app/build/offline-dependencies.txt; then
  echo "offline Android runtime contains a network client dependency" >&2
  exit 1
fi

echo "Offline Android connectivity boundary verified"
