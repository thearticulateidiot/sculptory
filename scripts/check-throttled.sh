#!/usr/bin/env bash
# A local stand-in for CI on a slow machine (Linux or WSL): the Gradle check (JUnit and both loaders' GameTests) and
# the Paper self-test for every redstone implementation, each pinned to a few CPU cores with taskset, as on GitHub's
# runners, where the server falls ticks behind and timing a fast machine never shows comes out.
#
#   scripts/check-throttled.sh [-c CORES] [-o OUT_DIR] [-s] [-p]
#
#   -c CORES    how many cores to pin to (default 2: cores 0 to CORES-1)
#   -o OUT_DIR  where the logs and the Paper server folders go (default build/check-throttled/<date-time>); it must
#               not exist yet
#   -s          skip the Gradle check (the Paper self-test only)
#   -p          skip the Paper self-test (the Gradle check only)
#
# Needs JAVA_HOME (or java on the PATH) pointing to a JDK 21, taskset, curl and jq or python3. Downloads for the Paper
# self-test are pinned and kept in OUT_DIR/../selftest-cache between runs (scripts/paper-selftest.sh). Prints one line
# per part and exits non-zero when any part failed. Run one at a time per checkout: two Gradle builds in the same
# folder overwrite each other's output.
set -uo pipefail

cores=2
out=""
gradle_check=1
paper=1
while getopts "c:o:sp" opt; do
  case "$opt" in
    c) cores="$OPTARG" ;;
    o) out="$OPTARG" ;;
    s) gradle_check=0 ;;
    p) paper=0 ;;
    *) sed -n '5,13p' "$0" >&2; exit 2 ;;
  esac
done
if ! [[ "$cores" =~ ^[1-9][0-9]*$ ]]; then
  echo "CORES must be a positive number, not '$cores'" >&2
  exit 2
fi
command -v taskset > /dev/null || { echo "taskset is not installed (util-linux)" >&2; exit 2; }

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -n "$out" ] || out="$repo/build/check-throttled/$(date +%Y%m%d-%H%M%S)"
if [ -e "$out" ]; then
  echo "$out exists already: name a new folder" >&2
  exit 2
fi
mkdir -p "$out"
out="$(cd "$out" && pwd)"
cpus="0-$((cores - 1))"
pin=(taskset -c "$cpus")
verdicts=()
failed=0

# Runs one part ("$1", logged to "$2"), pinned, and notes its verdict and wall time.
part() {
  local name="$1" log="$2"
  shift 2
  local start=$SECONDS
  echo "== $name (cores $cpus, log $log)"
  if "${pin[@]}" "$@" > "$log" 2>&1; then
    verdicts+=("PASS  $name  ($((SECONDS - start)) s)")
  else
    verdicts+=("FAIL  $name  ($((SECONDS - start)) s, see $log)")
    failed=1
  fi
}

# The checks a self-test report counts, for the verdict line.
counts() {
  grep -o '<testsuite[^>]*>' "$1" 2> /dev/null | sed -E 's/.* tests="([0-9]+)".* failures="([0-9]+)" errors="([0-9]+)".*/\1 checks, \2 failed, \3 errors/'
}

cd "$repo"
if [ "$gradle_check" -eq 1 ]; then
  part "gradle check" "$out/gradle-check.log" ./gradlew check --stacktrace
fi
if [ "$paper" -eq 1 ]; then
  part "paper dev jar" "$out/paper-devjar.log" ./gradlew :paper:devJar --stacktrace
  if [ "${verdicts[-1]:0:4}" = "PASS" ]; then
    for redstone in VANILLA EIGENCRAFT ALTERNATE_CURRENT; do
      server="$out/paper-$redstone"
      part "paper self-test $redstone" "$out/paper-$redstone.log" env SERVER_DIR="$server" \
        SELFTEST_CACHE="$(dirname "$out")/selftest-cache" "$repo/scripts/paper-selftest.sh" "$redstone"
      summary="$(counts "$server/selftest-report.xml")"
      [ -n "$summary" ] && verdicts[-1]="${verdicts[-1]}: $summary"
    done
  fi
fi

echo
echo "Throttled check, $cores cores ($out):"
printf '  %s\n' "${verdicts[@]}"
exit "$failed"
