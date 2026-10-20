#!/usr/bin/env bash
#
# Regenerate the golden parity fixtures from the Kotlin engines, then re-run the web
# parity suite. Wired to `npm run parity:export` in web/package.json.
#
# The Android engines are the source of truth. Stage58GoldenParityExportTest runs the real
# Kotlin engines over the shared scenario set and writes inputs and outputs to
# web/src/core/parity/kotlin-parity.json. The web parity suite then proves the TypeScript
# ports still match.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/home/openhands/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Exporting golden parity fixtures from the Kotlin engines"
(cd "$repo_root" && ./gradlew :app:testDebugUnitTest \
  --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest")

echo "==> Verifying the TypeScript ports against the fixture"
(cd "$repo_root/web" && npm run typecheck && npm test)

echo "==> Done. web/src/core/parity/kotlin-parity.json is up to date."
