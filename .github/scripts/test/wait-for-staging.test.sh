#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SCRIPT="$HERE/../wait-for-staging.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=$((FAILED + 1)); }
pass() { echo "ok:   $*"; }

mkdir -p "$WORK/bin" "$WORK/state"
cat > "$WORK/bin/curl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_CURL_LOG:?}"
url=''
for argument in "$@"; do
    url="$argument"
done
case "$url" in
    */repos/owner/repo/commits/staging)
        cat "$FAKE_STATE/commits.json"
        ;;
    */repos/owner/repo/compare/master...staging)
        cat "$FAKE_STATE/compare.json"
        ;;
    */repos/owner/repo/actions/workflows/other.yml/runs*)
        cat "$FAKE_STATE/runs.json"
        ;;
    */repos/owner/repo/actions/runs/*/jobs*)
        if [ -n "${FAKE_JOBS_SEQUENCE:-}" ]; then
            count=0
            [ -f "$FAKE_STATE/count" ] && count="$(cat "$FAKE_STATE/count")"
            count=$((count + 1))
            printf '%s\n' "$count" > "$FAKE_STATE/count"
            if [ "$count" -eq 1 ]; then cat "$FAKE_JOBS_SEQUENCE.pending"; else cat "$FAKE_JOBS_SEQUENCE.success"; fi
        else
            cat "$FAKE_STATE/jobs.json"
        fi
        ;;
    *)
        echo "unexpected URL: $url" >&2
        exit 22
        ;;
esac
FAKE
chmod +x "$WORK/bin/curl"

printf '%s\n' '{"sha":"abc123"}' > "$WORK/state/commits.json"
printf '%s\n' '{"status":"ahead"}' > "$WORK/state/compare.json"
printf '%s\n' '{"workflow_runs":[{"id":42,"head_sha":"abc123","event":"push","run_number":1,"status":"in_progress","conclusion":null}]}' > "$WORK/state/runs.json"
printf '%s\n' '{"jobs":[{"name":"deploy-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"},{"name":"smoke-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"}]}' > "$WORK/state/jobs.json"

run_gate() {
    PATH="$WORK/bin:$PATH" \
    GITHUB_API_URL="http://github.test" \
    FAKE_STATE="$WORK/state" \
    FAKE_CURL_LOG="$WORK/curl.log" \
    bash "$SCRIPT" --repository owner/repo --workflow other.yml --job deploy-staging --job smoke-staging --timeout 5 --interval 1 "$@"
}

if output="$(run_gate 2>&1)"; then
    pass "a successful deploy-staging job lets the gate pass"
else
    fail "the gate refused a successful deploy-staging job: $output"
fi
grep -q 'deploy-staging smoke-staging succeeded for abc123' <<<"$output" && pass "the gate names the successful jobs" || fail "success message missing"
grep -q 'Authorization: Bearer' "$WORK/curl.log" && fail "the gate sent an authorization header without a token" || pass "no authorization header without a token"

if output="$(run_gate --token secret-token-123 2>&1)"; then
    pass "the gate passes with an optional token"
else
    fail "the gate failed with an optional token: $output"
fi
grep -q 'Authorization: Bearer secret-token-123' "$WORK/curl.log" && pass "the token is sent to GitHub" || fail "the token is not sent"
grep -q 'secret-token-123' <<<"$output" && fail "the gate printed the token" || pass "the token is not printed"

printf '%s\n' '{"status":"diverged"}' > "$WORK/state/compare.json"
if output="$(run_gate --timeout 1 2>&1)"; then
    fail "the gate passed although staging does not contain master"
else
    case "$output" in
        *"does not contain master"*) pass "the gate waits while staging lacks master" ;;
        *) fail "unexpected missing-master message: $output" ;;
    esac
fi
printf '%s\n' '{"status":"ahead"}' > "$WORK/state/compare.json"

printf '%s\n' '{"jobs":[{"name":"deploy-staging","status":"completed","conclusion":"failure","html_url":"https://example.test/run/42"},{"name":"smoke-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"}]}' > "$WORK/state/jobs.json"
if output="$(run_gate 2>&1)"; then
    fail "the gate passed after a failed staging deployment"
else
    case "$output" in
        *"deploy-staging ended with 'failure'"*) pass "a failed staging deployment stops the gate" ;;
        *) fail "unexpected failed-job message: $output" ;;
    esac
fi

printf '%s\n' '{"jobs":[{"name":"deploy-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"},{"name":"smoke-staging","status":"completed","conclusion":"failure","html_url":"https://example.test/run/42"}]}' > "$WORK/state/jobs.json"
if output="$(run_gate 2>&1)"; then
    fail "the gate passed after a failed staging smoke test"
else
    case "$output" in
        *"smoke-staging ended with 'failure'"*) pass "a failed staging smoke test stops the gate" ;;
        *) fail "unexpected failed-smoke message: $output" ;;
    esac
fi

printf '%s\n' '{"jobs":[{"name":"deploy-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"},{"name":"smoke-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"}]}' > "$WORK/state/jobs.json"
printf '%s\n' '{"workflow_runs":[]}' > "$WORK/state/runs.json"
if output="$(run_gate --timeout 1 2>&1)"; then
    fail "the gate passed without a staging workflow run"
else
    case "$output" in
        *"no other.yml push run"*) pass "the gate waits for the staging run" ;;
        *) fail "unexpected no-run message: $output" ;;
    esac
fi

printf '%s\n' '{"workflow_runs":[{"id":42,"head_sha":"abc123","event":"push","run_number":1,"status":"completed","conclusion":"success"}]}' > "$WORK/state/runs.json"
printf '%s\n' '{"jobs":[{"name":"deploy-staging","status":"in_progress","conclusion":null,"html_url":"https://example.test/run/42"},{"name":"smoke-staging","status":"in_progress","conclusion":null,"html_url":"https://example.test/run/42"}]}' > "$WORK/state/jobs.pending"
printf '%s\n' '{"jobs":[{"name":"deploy-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"},{"name":"smoke-staging","status":"completed","conclusion":"success","html_url":"https://example.test/run/42"}]}' > "$WORK/state/jobs.success"
rm -f "$WORK/state/count"
if output="$(FAKE_JOBS_SEQUENCE="$WORK/state/jobs" run_gate 2>&1)"; then
    pass "the gate waits for an in-progress job and then passes"
else
    fail "the gate did not pass after the job turned green: $output"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "wait-for-staging: all checks passed"
else
    echo "wait-for-staging: $FAILED check(s) failed"
    exit 1
fi
