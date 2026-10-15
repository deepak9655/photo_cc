#!/usr/bin/env bash
#
# Compiles the engine and its tests with the plain Kotlin compiler and runs them
# on a JVM. No Android SDK, no emulator, no Gradle — which is the point: the
# engine is ordinary Kotlin that has to work on its own, so it can be tested in a
# couple of seconds on any machine.
#
#   tools/engine-test.sh [path-to-kotlinc] [path-to-kotlin-stdlib.jar]
#
# Defaults look for a Kotlin compiler on PATH and next to it.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$here"

kotlinc="${1:-$(command -v kotlinc || true)}"
stdlib="${2:-}"

if [[ -z "$kotlinc" ]]; then
  echo "kotlinc was not found. Pass its path: tools/engine-test.sh /opt/kotlinc/bin/kotlinc" >&2
  exit 2
fi

if [[ -z "$stdlib" ]]; then
  candidate="$(dirname "$kotlinc")/../lib/kotlin-stdlib.jar"
  if [[ -f "$candidate" ]]; then
    stdlib="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
  else
    echo "kotlin-stdlib.jar was not found next to kotlinc; pass it as the second argument." >&2
    exit 2
  fi
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

echo "compiling the engine"
"$kotlinc" -nowarn -jvm-target 17 \
  app/src/main/java/com/strata/editor/core/*.kt \
  app/src/test/java/com/strata/editor/core/*.kt \
  -d "$out"

status=0
for suite in EngineTest PsdTest ContentTest IoTest; do
  echo
  java -Xmx2g -cp "$out:$stdlib" "com.strata.editor.core.$suite" || status=$?
done

echo
if [[ $status -eq 0 ]]; then
  echo "everything passed"
else
  echo "at least one suite failed (exit $status)"
fi
exit $status
