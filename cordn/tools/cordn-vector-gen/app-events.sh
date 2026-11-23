#!/usr/bin/env bash
#
# Regenerates cordn/src/commonTest/resources/cordn/app-events/ from cordn-web, the reference
# cordn client (MIT, github.com/Cordn-msg/cordn-web). See README.md, "App-event vectors".
#
#   1. CordnAppEventVectorTest writes the envelopes OUR builders produce to
#      cordn/build/interop/app-events/ours-streams.json (it fails on a stale vector; that is fine).
#   2. app-events.mjs runs cordn-web's builders and interpretation code under Node and writes
#      theirs-cordn-web.json and ours-read-by-cordn-web.json.
#   3. Run the test again and read the diff of the vectors before committing them.
#
# Environment:
#   CORDN_WEB_DIR   cordn-web checkout (default ../cordn-web beside this repo); no install needed
#   NODE            a Node 22+ binary (default: node on PATH)
#
#   git clone https://github.com/Cordn-msg/cordn-web ../cordn-web
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
NODE="${NODE:-node}"
export CORDN_WEB_DIR="${CORDN_WEB_DIR:-$REPO/../cordn-web}"

[ -f "$CORDN_WEB_DIR/src/lib/chat/references.ts" ] || { echo "error: no cordn-web checkout at $CORDN_WEB_DIR (set CORDN_WEB_DIR)" >&2; exit 2; }
[ -d "$HERE/node_modules" ] || (cd "$HERE" && npm install --no-audit --no-fund)

echo "==> our envelopes"
"$REPO/gradlew" -p "$REPO" :cordn:jvmTest --tests '*CordnAppEventVectorTest*' -q || true
[ -f "$REPO/cordn/build/interop/app-events/ours-streams.json" ] || { echo "error: the test did not write ours-streams.json" >&2; exit 1; }

echo "==> cordn-web reads both"
"$NODE" --experimental-strip-types --no-warnings "$HERE/app-events.mjs"

echo "==> done; rerun :cordn:jvmTest and review the vector diff"
