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
# per part and exits non-zero when any part failed. Gradle runs with --no-daemon, so it never hands the build to a
# daemon started earlier (which would run on every core): the build JVM and every JVM it forks are children of the
# pinned process. While a part runs, the script checks that the pinned process's JVMs stay on the pinned cores, and a
# part where one does not fails as not throttled. Run one at a time per checkout: two Gradle builds in the same folder
# overwrite each other's output.
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
if [ "$gradle_check" -eq 0 ] && [ "$paper" -eq 0 ]; then
  echo "-s and -p together leave nothing to run" >&2
  exit 2
fi
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
mask="$(printf '%x' $(( (1 << cores) - 1 )))"
pin=(taskset -c "$cpus")
verdicts=()
failed=0

# Every process under $1, children first.
descendants() {
  local child
  for child in $(ps -o pid= --ppid "$1" 2> /dev/null); do
    echo "$child"
    descendants "$child"
  done
}

# Runs one part ("$1", logged to "$2") pinned to the cores, and notes its verdict and wall time. taskset pins only the
# process it starts and what that process starts, so while the part runs every JVM under it (the Gradle build, the
# GameTest servers it forks, the Paper server) has its affinity checked every 2 s; one that runs on other cores fails
# the part at once and its processes are stopped, so a check that is not throttled never passes as one.
part() {
  local name="$1" log="$2"
  shift 2
  local start=$SECONDS
  echo "== $name (cores $cpus, log $log)"
  "${pin[@]}" "$@" > "$log" 2>&1 &
  local top=$! checked=0 wrong="" pid seen=" "
  while kill -0 "$top" 2> /dev/null; do
    for pid in $top $(descendants "$top"); do
      case "$seen" in *" $pid "*) continue ;; esac
      [ "$(ps -o comm= -p "$pid" 2> /dev/null)" = "java" ] || continue
      seen="$seen$pid "
      local got
      got="$(taskset -p "$pid" 2> /dev/null | sed -E 's/.*: *//')"
      [ -n "$got" ] || continue
      checked=$((checked + 1))
      local what
      # A name for the log: the JVM's main class or jar.
      what="$(ps -o args= -p "$pid" 2> /dev/null \
        | grep -oE '[A-Za-z.]*(GradleDaemon|GradleWrapperMain|KnotServer|paper\.jar|BootstrapLauncher|Worker)[A-Za-z.]*' \
        | head -1)"
      echo "affinity: pid $pid mask $got ($what)" >> "$log.affinity"
      if [ "$got" != "$mask" ]; then wrong="pid $pid has affinity mask $got, not $mask"; fi
    done
    if [ -n "$wrong" ]; then
      kill $(descendants "$top") "$top" 2> /dev/null
      break
    fi
    sleep 2
  done
  wait "$top"
  local status=$?
  if [ -n "$wrong" ]; then
    verdicts+=("FAIL  $name  (not throttled: $wrong; see $log.affinity)")
    failed=1
  elif [ "$checked" -eq 0 ]; then
    verdicts+=("FAIL  $name  (no JVM seen to check its affinity; see $log)")
    failed=1
  elif [ "$status" -eq 0 ]; then
    verdicts+=("PASS  $name  ($((SECONDS - start)) s, $checked JVMs on mask $mask)")
  else
    verdicts+=("FAIL  $name  ($((SECONDS - start)) s, $checked JVMs on mask $mask, see $log)")
    failed=1
  fi
}

# The checks a self-test report counts, for the verdict line.
counts() {
  grep -o '<testsuite[^>]*>' "$1" 2> /dev/null | sed -E 's/.* tests="([0-9]+)".* failures="([0-9]+)" errors="([0-9]+)".*/\1 checks, \2 failed, \3 errors/'
}

cd "$repo"
# --no-daemon: a Gradle daemon started earlier runs on every core, and a build handed to it would not be throttled.
if [ "$gradle_check" -eq 1 ]; then
  part "gradle check" "$out/gradle-check.log" ./gradlew --no-daemon check --stacktrace
fi
if [ "$paper" -eq 1 ]; then
  part "paper dev jar" "$out/paper-devjar.log" ./gradlew --no-daemon :paper:devJar --stacktrace
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
printf '  %s\n' ${verdicts[@]+"${verdicts[@]}"}
exit "$failed"
