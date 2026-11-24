#!/usr/bin/env bash
set -euo pipefail

pr=''
self=''
timeout=1800
interval=20

usage() {
    cat >&2 <<'EOF'
usage: wait-for-pull-request-checks.sh --pr <number> --self <check name> [options]
  --pr <number>       pull request number to watch
  --self <name>       check run created by the current workflow run; ignored
  --timeout <seconds> overall timeout (default: 1800)
  --interval <seconds> poll interval (default: 20)
EOF
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --pr) pr="${2:-}"; shift 2 ;;
        --self) self="${2:-}"; shift 2 ;;
        --timeout) timeout="${2:-}"; shift 2 ;;
        --interval) interval="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

[ -n "$pr" ] || usage
[ -n "$self" ] || usage
[[ "$pr" =~ ^[0-9]+$ ]] || {
    echo "::error::--pr must be a pull request number." >&2
    exit 2
}
[[ "$timeout" =~ ^[0-9]+$ ]] && [ "$timeout" -gt 0 ] || {
    echo "::error::--timeout must be a positive integer." >&2
    exit 2
}
[[ "$interval" =~ ^[0-9]+$ ]] && [ "$interval" -gt 0 ] || {
    echo "::error::--interval must be a positive integer." >&2
    exit 2
}

repository="${GITHUB_REPOSITORY:-}"
run_id="${GITHUB_RUN_ID:-}"
[ -n "$repository" ] || {
    echo "::error::GITHUB_REPOSITORY is not set." >&2
    exit 2
}
[ -n "$run_id" ] || {
    echo "::error::GITHUB_RUN_ID is not set." >&2
    exit 2
}

deadline=$(( $(date +%s) + timeout ))
state=''

while :; do
    # gh pr checks exits non-zero while checks are pending or failing, but it
    # still prints the JSON list; only an empty answer is unusable.
    status=0
    checks="$(gh pr checks "$pr" --repo "$repository" --json name,bucket,link 2>/dev/null)" || status=$?
    if [ -z "$checks" ]; then
        echo "::error::Cannot read the checks of PR #${pr} in ${repository} (gh exit ${status})." >&2
        exit 1
    fi
    if ! jq -e 'type == "array"' >/dev/null 2>&1 <<<"$checks"; then
        echo "::error::GitHub returned an invalid check list for PR #${pr}." >&2
        exit 1
    fi

    # The pull request head is the staging commit, so the current promotion
    # job appears in its own check list. Ignore only this run's own job;
    # other runs' checks with the same name still count.
    other="$(jq -c --arg self "$self" --arg current "/runs/${run_id}/" '
        map(select(((.name == $self) and ((.link // "") | contains($current))) | not))
    ' <<<"$checks")"

    if [ "$(jq 'length' <<<"$other")" -eq 0 ]; then
        echo "::error::No checks other than this run's '${self}' check were reported for PR #${pr}; refusing to merge." >&2
        exit 1
    fi

    failed="$(jq -r '.[] | select(.bucket == "fail" or .bucket == "cancel") | "\(.name) (\(.bucket))"' <<<"$other")"
    if [ -n "$failed" ]; then
        echo "::error::PR #${pr} has failing checks:" >&2
        while IFS= read -r line; do
            printf '::error::%s\n' "$line" >&2
        done <<<"$failed"
        exit 1
    fi

    pending="$(jq -r '.[] | select(.bucket == "pending") | .name' <<<"$other")"
    if [ -z "$pending" ]; then
        unknown="$(jq -r '.[] | select(.bucket != "pass" and .bucket != "skipping" and .bucket != "neutral") | "\(.name) (\(.bucket))"' <<<"$other")"
        if [ -z "$unknown" ]; then
            echo "PR #${pr}: all checks other than this run's '${self}' check passed."
            exit 0
        fi
        state="unexpected check states: ${unknown//$'\n'/, }"
    else
        state="pending: ${pending//$'\n'/, }"
    fi

    echo "Waiting for PR #${pr} checks: ${state}"
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "::error::Timed out after ${timeout}s waiting for PR #${pr} checks: ${state}" >&2
        exit 1
    fi
    sleep "$interval"
done
