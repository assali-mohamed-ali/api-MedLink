#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SCRIPT="$HERE/../wait-for-pull-request-checks.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0
fail() { echo "FAIL: $*" >&2; FAILED=$((FAILED + 1)); }
pass() { echo "ok:   $*"; }

mkdir -p "$WORK/bin" "$WORK/state"
cat > "$WORK/bin/gh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_GH_LOG:?}"
if [ -n "${FAKE_SEQUENCE:-}" ]; then
    count=0
    [ -f "$FAKE_STATE/count" ] && count="$(cat "$FAKE_STATE/count")"
    count=$((count + 1))
    printf '%s\n' "$count" > "$FAKE_STATE/count"
    if [ "$count" -eq 1 ]; then
        cat "$FAKE_SEQUENCE.pending"
    else
        cat "$FAKE_SEQUENCE.pass"
    fi
else
    cat "$FAKE_STATE/checks.json"
fi
FAKE
chmod +x "$WORK/bin/gh"

self_pending='{"name":"Promote staging to prod","bucket":"pending","link":"https://github.test/actions/runs/999/job/1"}'
self_cancelled_old='{"name":"Promote staging to prod","bucket":"cancel","link":"https://github.test/actions/runs/111/job/1"}'
other_pass='{"name":"build-test","bucket":"pass","link":"https://github.test/actions/runs/123/job/2"}'
other_pending='{"name":"build-test","bucket":"pending","link":"https://github.test/actions/runs/123/job/2"}'
other_fail='{"name":"build-test","bucket":"fail","link":"https://github.test/actions/runs/123/job/2"}'
other_skip='{"name":"dependency-review","bucket":"skipping","link":"https://github.test/actions/runs/123/job/3"}'

write_checks() {
    printf '[%s]\n' "$(IFS=,; echo "$*")" > "$WORK/state/checks.json"
}

run_gate() {
    PATH="$WORK/bin:$PATH" \
    GITHUB_REPOSITORY="owner/repo" \
    GITHUB_RUN_ID="999" \
    FAKE_STATE="$WORK/state" \
    FAKE_GH_LOG="$WORK/gh.log" \
    bash "$SCRIPT" --pr 141 --self "Promote staging to prod" --timeout 5 --interval 1 "$@"
}

write_checks "$self_pending" "$other_pass"
if output="$(run_gate 2>&1)"; then
    pass "the gate ignores this run's own pending check and passes when the other checks are green"
else
    fail "the gate did not pass despite green independent checks: $output"
fi
grep -q 'all checks other than this run' <<<"$output" && pass "the gate explains which check it ignored" || fail "success message missing"

write_checks "$self_pending" "$other_skip"
if output="$(run_gate 2>&1)"; then
    pass "a skipped independent check does not block the gate"
else
    fail "the gate refused a skipped independent check: $output"
fi

printf '[%s,%s]\n' "$self_pending" "$other_pending" > "$WORK/state/checks.pending"
printf '[%s,%s]\n' "$self_pending" "$other_pass" > "$WORK/state/checks.pass"
rm -f "$WORK/state/count"
if output="$(FAKE_SEQUENCE="$WORK/state/checks" run_gate 2>&1)"; then
    pass "the gate waits for an independent pending check and then passes"
else
    fail "the gate did not pass after the independent check turned green: $output"
fi

write_checks "$self_pending" "$other_fail"
if output="$(run_gate 2>&1)"; then
    fail "the gate passed after an independent check failed"
else
    case "$output" in
        *"build-test (fail)"*) pass "an independent failed check stops the gate" ;;
        *) fail "unexpected independent-failure message: $output" ;;
    esac
fi

write_checks "$self_cancelled_old" "$other_pass"
if output="$(run_gate 2>&1)"; then
    fail "the gate ignored a cancelled check from another run"
else
    case "$output" in
        *"Promote staging to prod (cancel)"*) pass "only this run's own check is ignored" ;;
        *) fail "unexpected old-cancelled message: $output" ;;
    esac
fi

write_checks "$self_pending"
if output="$(run_gate 2>&1)"; then
    fail "the gate passed when its own check was the only check"
else
    case "$output" in
        *"No checks other than this run's 'Promote staging to prod' check"*) pass "the gate refuses a PR with no independent checks" ;;
        *) fail "unexpected no-independent-checks message: $output" ;;
    esac
fi

write_checks
if output="$(run_gate 2>&1)"; then
    fail "the gate passed an empty check list"
else
    case "$output" in
        *"No checks other than this run's"*) pass "an empty check list is rejected" ;;
        *) fail "unexpected empty-check-message: $output" ;;
    esac
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "wait-for-pull-request-checks: all checks passed"
else
    echo "wait-for-pull-request-checks: $FAILED check(s) failed"
    exit 1
fi
