#!/bin/sh
# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
#
# Black-box session driver for Scan Kiwi (see README-for-testers.md).
#   bb.sh start [ar|en]     start the simulated phone with the app (takes 1-3 minutes)
#   bb.sh "tap \"Scan\""    run one command; prints the screenshot path, then the screen text
#   bb.sh last              print the latest result again
#   bb.sh stop              end the session
#
# Environment (all optional): BB_SESSION (session folder), BB_TIMEOUT (seconds to wait
# for one command, default 300), KIWI_TOOLS (folder with jdk-*, android-sdk, gradle-*),
# JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME (taken as they are when set).
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
SESSION=${BB_SESSION:-$REPO/build/blackbox/session}
TIMEOUT=${BB_TIMEOUT:-300}

winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

setup_env() {
    TOOLS=${KIWI_TOOLS:-}
    if [ -z "$TOOLS" ] && [ -d "$REPO/.tools" ]; then TOOLS="$REPO/.tools"; fi
    if [ -z "$TOOLS" ] && [ -d "/e/fast kiwi pro neq/kiwi-scan/.tools" ]; then TOOLS="/e/fast kiwi pro neq/kiwi-scan/.tools"; fi
    if [ -n "$TOOLS" ]; then
        if [ -z "${JAVA_HOME:-}" ]; then
            for j in "$TOOLS"/jdk-*; do [ -d "$j" ] && JAVA_HOME=$(winpath "$j"); done
            export JAVA_HOME
        fi
        if [ -z "${ANDROID_HOME:-}" ] && [ -d "$TOOLS/android-sdk" ]; then
            ANDROID_HOME=$(winpath "$TOOLS/android-sdk"); ANDROID_SDK_ROOT=$ANDROID_HOME; export ANDROID_HOME ANDROID_SDK_ROOT
        fi
        if [ -z "${ANDROID_USER_HOME:-}" ] && [ -d "$TOOLS/android-user" ]; then
            ANDROID_USER_HOME=$(winpath "$TOOLS/android-user"); export ANDROID_USER_HOME
        fi
        if [ -z "${GRADLE_USER_HOME:-}" ]; then GRADLE_USER_HOME=$(winpath "$TOOLS/gh-gates"); export GRADLE_USER_HOME; fi
        for j in "$TOOLS"/jdk-*/bin "$TOOLS"/gradle-*/bin; do [ -d "$j" ] && PATH="$j:$PATH"; done
        export PATH
    fi
    if command -v gradle >/dev/null 2>&1; then GRADLE=gradle; else GRADLE="$REPO/gradlew"; fi
}

num() { printf '%03d' "$1"; }

show() {
    n=$1
    out="$SESSION/out"
    echo "screenshot: $(winpath "$out/$(num "$n").png")"
    for extra in "$out/$(num "$n")"-received-*.png; do [ -f "$extra" ] && echo "received file picture: $(winpath "$extra")"; done
    echo "screen text: $(winpath "$out/$(num "$n").txt")"
    echo
    cat "$out/$(num "$n").txt"
}

wait_done() {
    n=$1
    waited=0
    while [ ! -f "$SESSION/out/$(num "$n").done" ]; do
        if [ -f "$SESSION/ended" ] && [ ! -f "$SESSION/out/$(num "$n").done" ]; then
            echo "the session has ended (see $SESSION/gradle.log)" >&2; exit 2
        fi
        if [ "$waited" -ge $((TIMEOUT * 5)) ]; then
            echo "no answer after $TIMEOUT s (the session may still be working; try: bb.sh last)" >&2; exit 3
        fi
        sleep 0.2
        waited=$((waited + 1))
    done
}

cmd=${1:-}
case "$cmd" in
    start)
        lang=${2:-ar}
        if [ -f "$SESSION/pid" ] && [ ! -f "$SESSION/ended" ]; then
            echo "a session is already running in $SESSION (bb.sh stop first)" >&2; exit 1
        fi
        rm -rf "$SESSION"
        mkdir -p "$SESSION/in" "$SESSION/out"
        echo 0 > "$SESSION/next"
        setup_env
        (
            cd "$REPO"
            BB_SESSION=$(winpath "$SESSION") BB_LANG=$lang "$GRADLE" --no-daemon -q :app:testFullDebugUnitTest \
                --tests 'com.fastkiwi.scan.gates.BlackBoxSession' --rerun > "$SESSION/gradle.log" 2>&1 || true
            echo ended > "$SESSION/ended"
        ) &
        echo $! > "$SESSION/pid"
        TIMEOUT=900
        wait_done 0
        show 0
        ;;
    stop)
        if [ -d "$SESSION/in" ]; then
            touch "$SESSION/in/stop"
            i=0
            while [ ! -f "$SESSION/ended" ] && [ $i -lt 300 ]; do sleep 0.2; i=$((i + 1)); done
        fi
        echo "session stopped"
        ;;
    last)
        n=$(cat "$SESSION/next")
        show "$n"
        ;;
    ""|-h|--help|help)
        sed -n '4,13p' "$0"
        ;;
    *)
        [ -d "$SESSION/in" ] || { echo "no session (bb.sh start first)" >&2; exit 1; }
        [ -f "$SESSION/ended" ] && { echo "the session has ended (bb.sh start)" >&2; exit 2; }
        n=$(( $(cat "$SESSION/next") + 1 ))
        printf '%s\n' "$*" > "$SESSION/in/$(num "$n").cmd.tmp"
        mv "$SESSION/in/$(num "$n").cmd.tmp" "$SESSION/in/$(num "$n").cmd"
        echo "$n" > "$SESSION/next"
        wait_done "$n"
        show "$n"
        ;;
esac
