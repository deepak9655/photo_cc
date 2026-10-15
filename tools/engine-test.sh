#!/usr/bin/env bash
#
# Engine test runner.
#
# The PSD engine (app/src/main/java/com/photocc/app/core and .../doc) is plain
# Kotlin with no Android dependencies, so it is compiled and exercised on a
# desktop JVM here — no emulator, no Android SDK:
#
#   1. fetch a small corpus of *real* Photoshop files (third-party test files,
#      not stored in this repository) unless a directory is provided;
#   2. compile the engine plus the self-test with kotlinc;
#   3. run the self-test: the Kotlin reader is compared layer-by-layer against
#      values produced by `ag-psd`, the writer is round-tripped, and the editing
#      session (undo/redo, crop, merge, flatten, text) is checked;
#   4. hand the files the engine wrote to an independent reader (ag-psd) and
#      verify them again from the outside.
#
# Requirements: JDK 11+ (java), a Kotlin compiler (kotlinc) and Node 18+.
# Overrides:
#   PHOTO_CC_KC        path to kotlinc            (default: kotlinc on PATH)
#   PHOTO_CC_STDLIB    path to kotlin-stdlib.jar  (default: next to kotlinc)
#   PHOTO_CC_FIXTURES  directory of .psd files    (default: tools/engine/fixtures)
#   PHOTO_CC_OUT       output directory           (default: build/engine-test)
#
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

fixtures="${PHOTO_CC_FIXTURES:-$root/tools/engine/fixtures}"
out="${PHOTO_CC_OUT:-$root/build/engine-test}"
classes="$out/classes"
kc="${PHOTO_CC_KC:-kotlinc}"
stdlib="${PHOTO_CC_STDLIB:-}"

if ! command -v java >/dev/null 2>&1; then
  echo "java not found: install a JDK 11 or newer" >&2
  exit 2
fi
if ! command -v "$kc" >/dev/null 2>&1; then
  echo "kotlinc not found: install a Kotlin compiler or set PHOTO_CC_KC" >&2
  exit 2
fi

if [ -z "$stdlib" ] && [ -x "$(command -v "$kc")" ]; then
  candidate="$(dirname "$(command -v "$kc")")/../lib/kotlin-stdlib.jar"
  [ -f "$candidate" ] && stdlib="$candidate"
fi

mkdir -p "$fixtures" "$out"

# ---------------------------------------------------------------- 1. fixtures
count=$(find "$fixtures" -maxdepth 1 -name '*.psd' | wc -l | tr -d ' ')
if [ "$count" -lt 5 ]; then
  echo "== fetching the sample PSD corpus =="
  node tools/engine/fetch-fixtures.mjs "$fixtures" || {
    echo "could not fetch fixtures (offline?); the engine test still runs with whatever is present" >&2
  }
fi

# ------------------------------------------------------------- 2. compile
echo "== compiling the engine =="
rm -rf "$classes"
mkdir -p "$classes"
# shellcheck disable=SC2046
"$kc" $(find app/src/main/java/com/photocc/app/core app/src/main/java/com/photocc/app/doc -name '*.kt') \
  app/src/test/java/com/photocc/app/core/*.kt \
  -nowarn -jvm-target 17 -d "$classes"

# ----------------------------------------------------------------- 3. run
echo "== engine self-test =="
classpath="$classes${stdlib:+:$stdlib}"
PHOTO_CC_FIXTURES="$fixtures" PHOTO_CC_OUT="$out" \
  java -Xmx2g -cp "$classpath" com.photocc.app.core.EngineSelfTest

# ------------------------------------------------- 4. independent verification
if [ -d tools/engine/node_modules ]; then
  if [ ! -f "$fixtures/expected.json" ]; then
    echo "== building reference values with ag-psd =="
    PHOTO_CC_FIXTURES="$fixtures" node tools/engine/compare-read.mjs
  fi
  echo
  echo "== verifying the written files with ag-psd =="
  PHOTO_CC_OUT="$out" node tools/engine/verify-write.mjs
else
  echo
  echo "(skipping the ag-psd cross-check: run 'npm install' in tools/engine to enable it)"
fi

echo
echo "engine artefacts: $out"
