#!/usr/bin/env bash
# Tests that configured NVD credentials always select the primary SCA scanner.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/sca-policy.sh"
failures=0

field() {
    sed -n "s/^$2=//p" <<<"$1"
}

expect() {
    local name="$1" expected_scanner="$2" expected_allowed="$3"
    shift 3
    local out scanner allowed reason
    out="$("$SCRIPT" "$@")"
    scanner="$(field "$out" scanner)"
    allowed="$(field "$out" fallback_allowed)"
    reason="$(field "$out" reason)"

    if [ "$scanner" != "$expected_scanner" ] || [ "$allowed" != "$expected_allowed" ]; then
        echo "FAIL: $name: expected scanner=$expected_scanner fallback_allowed=$expected_allowed, got scanner=$scanner fallback_allowed=$allowed" >&2
        failures=$((failures + 1))
    fi
    if [ -z "$reason" ]; then
        echo "FAIL: $name: empty reason" >&2
        failures=$((failures + 1))
    fi
}

# If the key exists, the primary NVD-backed scanner must run on every supported event.
expect 'NVD key on PR uses Dependency-Check' dependency-check true pull_request refs/pull/1/merge true false
expect 'NVD key on master push uses Dependency-Check' dependency-check true push refs/heads/master true false
expect 'NVD key on staging push uses Dependency-Check' dependency-check true push refs/heads/staging true false
expect 'NVD key on feature push uses Dependency-Check' dependency-check true push refs/heads/fix/54-sca-fallback true false
expect 'NVD key on schedule' dependency-check true schedule refs/heads/master true false
expect 'NVD key on manual run' dependency-check true workflow_dispatch refs/heads/master true false

# Missing key: Trivy is an explicit fallback for PRs, schedules, manual runs, and feature branches.
expect 'missing key on PR' trivy true pull_request refs/pull/1/merge false false
expect 'missing key on schedule' trivy true schedule refs/heads/master false false
expect 'missing key on manual run' trivy true workflow_dispatch refs/heads/master false false
expect 'missing key on feature branch' trivy true push refs/heads/fix/54-sca-fallback false false

# Missing key: release branches fail unless the fallback is explicitly accepted.
expect 'missing key on master' trivy false push refs/heads/master false false
expect 'missing key on staging' trivy false push refs/heads/staging false false
expect 'missing key on prod' trivy false push refs/heads/prod false false
expect 'missing key on v* tag' trivy false push refs/tags/v1.0.0 false false
expect 'missing key on master, fallback accepted' trivy true push refs/heads/master false true
expect 'missing key on master, fallback accepted (TRUE)' trivy true push refs/heads/master false TRUE

# Unknown events are denied, even if a key is configured.
expect 'missing key on unknown event' trivy false workflow_run refs/heads/master false false
expect 'NVD key on unknown event' trivy false workflow_run refs/heads/master true false

if [ "$failures" -ne 0 ]; then
    echo "$failures SCA policy test(s) failed" >&2
    exit 1
fi

echo 'SCA policy tests passed (18 cases)'
