#!/bin/sh
# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
#
# Runs the torture suite (stress, fuzz, chaos, performance, UI monkey; ~15-20 minutes).
# The tests are skipped in the normal unit suite; KIWI_TORTURE=1 turns them on.
#   tools/blackbox/torture.sh                 # everything
#   tools/blackbox/torture.sh '*TortureFuzz*' # one part
# Results: app/build/test-results/testFullDebugUnitTest/, timings in app/build/torture-perf.txt,
# fuzz outcomes in app/build/torture-fuzz.txt, monkey failures (seed + replay trail) in
# app/build/torture-monkey-<lang>.txt.
set -eu
REPO=$(cd "$(dirname "$0")/../.." && pwd)
FILTER=${1:-com.fastkiwi.scan.gates.Torture*}
cd "$REPO"
rm -f app/build/torture-perf.txt app/build/torture-fuzz.txt app/build/torture-monkey-*.txt
if command -v gradle >/dev/null 2>&1; then GRADLE=gradle; else GRADLE=./gradlew; fi
KIWI_TORTURE=1 "$GRADLE" --no-daemon -q :app:testFullDebugUnitTest --tests "$FILTER" --rerun
status=$?
[ -f app/build/torture-perf.txt ] && cat app/build/torture-perf.txt
exit $status
