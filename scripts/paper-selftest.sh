#!/usr/bin/env bash
# The Paper plugin's self-test on a real Paper server, as CI runs it (.github/workflows/paper-selftest.yml calls this
# script, so the two cannot drift). Paper has no GameTests: the plugin's dev jar (./gradlew :paper:devJar) runs its
# self-test at start as a fake player, writes a JUnit-style report and stops the server.
#
#   scripts/paper-selftest.sh <REDSTONE> [download|redstone|run|verify|implementation|all]
#
# <REDSTONE> is Paper's misc.redstone-implementation: VANILLA, EIGENCRAFT or ALTERNATE_CURRENT. The steps run in the
# order listed; "all" (the default) runs every one. The server folder is $SERVER_DIR (default paper-server, relative to
# the current directory); "download" makes it and refuses one that holds a world already.
#
# Every download is pinned: Paper by its build and SHA-256 (PaperMC's Fill v3 API), WorldEdit and WorldGuard by their
# Modrinth version ids and SHA-512. The pins are the environment variables below, with the defaults CI uses.
# SELFTEST_CACHE (optional, local runs) names a folder that keeps the verified downloads and the server jar Paper
# patches at its first start, so a fresh server folder does not download them again.
set -euo pipefail

: "${PAPER_VERSION:=1.21.1}"
: "${PAPER_BUILD:=133}"
: "${PAPER_SHA256:=39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9}"
# The checks the fake-player run makes: fewer means a group was skipped or never ran.
: "${SELFTEST_MIN_TESTS:=171}"
# WorldEdit 7.3.9 and WorldGuard 7.0.12 (Modrinth version ids), the versions the plugin is built against.
: "${WORLDEDIT_VERSION_ID:=Bu1zaaoc}"
: "${WORLDEDIT_SHA512:=ce19097ab93a3ff75a929a1efec58138adc5b700f876ec8ac7a96c97d98d77a06f22661f0418c3407d644ca2d9713fde417d5789c79864d9c2094f203f372c76}"
: "${WORLDGUARD_VERSION_ID:=J66QOTLZ}"
: "${WORLDGUARD_SHA512:=5917a0bf2de577021a3881e440386acefa285dfb3625c4a69be12922e4bc03cfea16ea07335f0cc71d4ea516f19477679001f6968d853969a89bfee0c9ecd7b9}"
: "${SERVER_DIR:=paper-server}"
: "${SELFTEST_CACHE:=}"

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"

fail() {
  # GitHub Actions shows ::error:: lines as annotations; elsewhere they read as plain errors.
  echo "::error::$*" >&2
  exit 1
}

if [ $# -lt 1 ]; then
  echo "usage: $0 <VANILLA|EIGENCRAFT|ALTERNATE_CURRENT> [download|redstone|run|verify|implementation|all]..." >&2
  exit 2
fi
REDSTONE="$1"
shift
case "$REDSTONE" in
  VANILLA | EIGENCRAFT | ALTERNATE_CURRENT) ;;
  *) fail "unknown redstone implementation '$REDSTONE' (VANILLA, EIGENCRAFT or ALTERNATE_CURRENT)" ;;
esac
steps=("$@")
[ ${#steps[@]} -eq 0 ] && steps=(all)

# One field of a JSON document on stdin, by a jq path. jq where it is installed (as on GitHub's runners), else Python.
json() {
  if command -v jq > /dev/null; then
    jq -r "$1"
  else
    python3 -I -c 'import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].split("|"):
    value = value[int(key)] if key.isdigit() else value[key]
print(value)' "$2"
  fi
}

# Downloads $1 to $2 and checks it against the hash $4 with the sum tool $3; with SELFTEST_CACHE, a verified copy there
# is used instead, and a fresh download is kept there.
fetch() {
  local url="$1" out="$2" sum="$3" hash="$4"
  if [ -n "$SELFTEST_CACHE" ] && [ -f "$SELFTEST_CACHE/$hash" ]; then
    cp "$SELFTEST_CACHE/$hash" "$out"
  else
    curl -fsSL -o "$out" "$url"
  fi
  echo "${hash}  ${out}" | "$sum" -c -
  if [ -n "$SELFTEST_CACHE" ] && [ ! -f "$SELFTEST_CACHE/$hash" ]; then
    mkdir -p "$SELFTEST_CACHE"
    cp "$out" "$SELFTEST_CACHE/$hash"
  fi
}

step_download() {
  if [ -e "$SERVER_DIR/world" ]; then fail "$SERVER_DIR holds a world already: use a fresh server folder"; fi
  mkdir -p "$SERVER_DIR/plugins"
  (
    cd "$SERVER_DIR"
    url=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}" \
      | json '.downloads["server:default"].url' 'downloads|server:default|url')
    fetch "$url" paper.jar sha256sum "$PAPER_SHA256"
    for plugin in WORLDEDIT WORLDGUARD; do
      id_var="${plugin}_VERSION_ID"; sha_var="${plugin}_SHA512"
      file_url=$(curl -fsSL "https://api.modrinth.com/v2/version/${!id_var}" | json '.files[0].url' 'files|0|url')
      fetch "$file_url" "plugins/${plugin,,}.jar" sha512sum "${!sha_var}"
    done
    # The vanilla server jar Paper patches at its first start (it checks the jar's hash itself).
    if [ -n "$SELFTEST_CACHE" ] && [ -d "$SELFTEST_CACHE/paper-cache" ]; then cp -r "$SELFTEST_CACHE/paper-cache" cache; fi
    echo "eula=true" > eula.txt
    printf 'server-port=25580\nonline-mode=false\nspawn-protection=16\n' > server.properties
  )
}

step_redstone() {
  mkdir -p "$SERVER_DIR/config"
  local file="$SERVER_DIR/config/paper-world-defaults.yml"
  if [ -f "$file" ]; then
    sed -i -E "s/^(  redstone-implementation: ).*/\1${REDSTONE}/" "$file"
  else
    # Paper writes the whole file at its first start and keeps what is set here (version 31: Paper 1.21.1).
    printf '_version: 31\nmisc:\n  redstone-implementation: %s\n' "$REDSTONE" > "$file"
  fi
  grep -n 'redstone-implementation' "$file"
}

step_run() {
  local jars=("$repo"/modules/paper/build/libs/sculptory-paper-*-dev.jar)
  if [ "${#jars[@]}" -ne 1 ] || [ ! -f "${jars[0]}" ]; then
    fail "Expected one dev jar in modules/paper/build/libs, found: ${jars[*]}"
  fi
  local jar
  jar="$(realpath "${jars[0]}")"
  echo "Self-test jar: $jar"
  (
    cd "$SERVER_DIR"
    "$java" -Xmx2G -Dsculptory.selftest.enabled=true -Dsculptory.checkPermitBounds=true \
      -Dsculptory.selftest="$PWD/selftest-report.xml" -Dsculptory.selftest.stop=true \
      -jar paper.jar --add-plugin="$jar" --nogui < /dev/null
  )
  if [ -n "$SELFTEST_CACHE" ] && [ -d "$SERVER_DIR/cache" ] && [ ! -d "$SELFTEST_CACHE/paper-cache" ]; then
    mkdir -p "$SELFTEST_CACHE"
    cp -r "$SERVER_DIR/cache" "$SELFTEST_CACHE/paper-cache"
  fi
}

step_verify() {
  (
    cd "$SERVER_DIR"
    test -f selftest-report.xml
    grep -o '<testsuite[^>]*>' selftest-report.xml
    grep -q 'failures="0" errors="0"' selftest-report.xml
    tests=$(grep -o '<testsuite[^>]*>' selftest-report.xml | sed -E 's/.* tests="([0-9]+)".*/\1/')
    echo "${tests} checks ran (at least ${SELFTEST_MIN_TESTS} expected)"
    test "${tests}" -ge "${SELFTEST_MIN_TESTS}"
  )
}

# The redstone implementation in force: the one asked for, as the report states it.
step_implementation() {
  (
    cd "$SERVER_DIR"
    grep -n 'redstone-implementation' config/paper-world-defaults.yml
    grep -q "redstone ${REDSTONE}" selftest-report.xml
  )
}

for step in "${steps[@]}"; do
  case "$step" in
    download) step_download ;;
    redstone) step_redstone ;;
    run) step_run ;;
    verify) step_verify ;;
    implementation) step_implementation ;;
    all) step_download; step_redstone; step_run; step_verify; step_implementation ;;
    *) fail "unknown step '$step' (download, redstone, run, verify, implementation or all)" ;;
  esac
done
