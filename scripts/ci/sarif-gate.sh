#!/usr/bin/env bash
# Prints every result of the given SARIF files and exits 1 when one of them
# comes from a rule whose security-severity is >= THRESHOLD (CVSS-like, 0-10).
# Usage: scripts/ci/sarif-gate.sh <threshold> <file.sarif>...
set -euo pipefail

threshold="$1"
shift
[ "$#" -gt 0 ] || { echo "no SARIF file given" >&2; exit 2; }

blocking=0
for f in "$@"; do
  [ -f "$f" ] || { echo "missing SARIF file: $f" >&2; exit 2; }
  report=$(jq -r --argjson t "$threshold" '
    .runs[]
    | ([.tool.driver.rules[]?, .tool.extensions[]?.rules[]?]
       | map({key: .id, value: (.properties["security-severity"] // "0" | tonumber)})
       | from_entries) as $sev
    | .results[]
    | (.ruleId // .rule.id) as $id
    | ($sev[$id] // 0) as $s
    | [(if $s >= $t then "BLOCK" else "info " end), ($s | tostring), $id,
       ((.locations[0].physicalLocation.artifactLocation.uri // "?") + ":"
        + (.locations[0].physicalLocation.region.startLine // 0 | tostring)),
       (.message.text | split("\n")[0])]
    | join("  ")' "$f")
  count=$(printf '%s' "$report" | grep -c . || true)
  echo "== $f: $count result(s)"
  [ -z "$report" ] || printf '%s\n' "$report"
  if printf '%s\n' "$report" | grep -q '^BLOCK'; then blocking=1; fi
done

if [ "$blocking" -ne 0 ]; then
  echo "::error::Security findings with severity >= $threshold (lines marked BLOCK above)"
  exit 1
fi
echo "No finding with severity >= $threshold"
