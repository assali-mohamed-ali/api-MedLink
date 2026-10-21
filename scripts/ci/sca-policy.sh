#!/usr/bin/env bash
# Decide which SCA scanner this run may use, and whether the fallback is allowed.
# Prints key=value lines for $GITHUB_OUTPUT; never fails on its own so the scan
# can still run and publish findings before the policy gate rejects it.
#
# Usage: scripts/ci/sca-policy.sh <event> <ref> <has_nvd_key> <allow_fallback>
#   event           GitHub event name (push, pull_request, schedule, workflow_dispatch, ...)
#   ref             full git ref (refs/heads/master, refs/tags/v1.0.0, ...)
#   has_nvd_key     true when secrets.NVD_API_KEY is set
#   allow_fallback  true when vars.ALLOW_TRIVY_FALLBACK explicitly accepts the fallback
set -uo pipefail

event="${1:-}"
ref="${2:-}"
has_key="${3:-false}"
allow_fallback="${4:-false}"

truthy() {
    case "${1:-}" in
        true | TRUE | True | 1 | yes | YES) return 0 ;;
        *) return 1 ;;
    esac
}

is_release_ref() {
    case "$1" in
        refs/heads/master | refs/heads/staging | refs/heads/prod | refs/tags/v*) return 0 ;;
        *) return 1 ;;
    esac
}

scanner='trivy'
fallback_allowed='false'
reason=''

if truthy "$has_key"; then
    scanner='dependency-check'
    fallback_allowed='true'
    reason='NVD_API_KEY is set: OWASP Dependency-Check (NVD) is the primary scanner.'
else
    case "$event" in
        pull_request)
            fallback_allowed='true'
            reason='NVD_API_KEY is not set: this pull request uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
            ;;
        schedule)
            fallback_allowed='true'
            reason='NVD_API_KEY is not set: the scheduled run uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
            ;;
        workflow_dispatch)
            fallback_allowed='true'
            reason='NVD_API_KEY is not set: this manual run uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
            ;;
        push)
            if is_release_ref "$ref"; then
                if truthy "$allow_fallback"; then
                    fallback_allowed='true'
                    reason='NVD_API_KEY is not set: the release-branch Trivy fallback is explicitly accepted by ALLOW_TRIVY_FALLBACK=true.'
                else
                    fallback_allowed='false'
                    reason='NVD_API_KEY is not set: release branches require the primary OWASP Dependency-Check scanner. Set the NVD_API_KEY secret, or set the repository variable ALLOW_TRIVY_FALLBACK=true to accept the Trivy fallback explicitly.'
                fi
            else
                fallback_allowed='true'
                reason='NVD_API_KEY is not set: a non-release branch uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
            fi
            ;;
        *)
            fallback_allowed='false'
            reason="NVD_API_KEY is not set and event '${event}' is not allowed to use the Trivy fallback."
            ;;
    esac
fi

printf 'scanner=%s\n' "$scanner"
printf 'fallback_allowed=%s\n' "$fallback_allowed"
printf 'reason=%s\n' "$reason"
