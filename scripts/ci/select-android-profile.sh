#!/usr/bin/env bash
set -euo pipefail
profile=release
unit_tests=(testProductionDebugUnitTest)
if [[ "${FULL_UNIT_SUITE:-false}" == true || "${FULL_ANDROID_MATRIX:-false}" == true ]]; then
  reason="${DIAGNOSTIC_REASON:-}"
  if [[ -z "${reason//[[:space:]]/}" ]]; then
    echo 'Full regression requires a production screenshot or manual acceptance failure reason.' >&2
    exit 1
  fi
  profile=full
fi
if [[ "${FULL_ANDROID_MATRIX:-false}" == true ]]; then
  unit_tests=(testOfflineDebugUnitTest testProductionDebugUnitTest)
fi
./gradlew "${unit_tests[@]}" "-PandroidTestProfile=$profile" \
  lintOfflineRelease assembleOfflineDebug lintProductionDebug assembleProductionDebug --no-daemon
