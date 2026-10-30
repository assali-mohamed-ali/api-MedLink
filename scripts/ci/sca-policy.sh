#!/usr/bin/env bash
# Decide which SCA scanner this run may use, and whether the fallback is allowed.
# A configured NVD API key means every supported event runs OWASP Dependency-Check.
# Trivy is only an explicit fallback when the key is unavailable.
#
# Usage: scripts/ci/sca-policy.sh <event> <ref> <has_nvd_key> <allow_fallback>
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

case "$event" in
    pull_request | schedule | workflow_dispatch)
        if truthy "$has_key"; then
            scanner='dependency-check'
            fallback_allowed='true'
            reason='NVD_API_KEY is set: OWASP Dependency-Check (NVD) is the primary scanner.'
        else
            fallback_allowed='true'
            case "$event" in
                pull_request)
                    reason='NVD_API_KEY is not set: the PR uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
                    ;;
                schedule)
                    reason='NVD_API_KEY is not set: the scheduled run uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
                    ;;
                workflow_dispatch)
                    reason='NVD_API_KEY is not set: this manual run uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
                    ;;
            esac
        fi
        ;;
    push)
        if truthy "$has_key"; then
            scanner='dependency-check'
            fallback_allowed='true'
            reason='NVD_API_KEY is set: OWASP Dependency-Check (NVD) is the primary scanner.'
        elif is_release_ref "$ref"; then
            if truthy "$allow_fallback"; then
                fallback_allowed='true'
                reason='NVD_API_KEY is not set: the release-branch Trivy fallback is explicitly accepted by ALLOW_TRIVY_FALLBACK=true.'
            else
                fallback_allowed='false'
                reason='NVD_API_KEY is not set: release branches require OWASP Dependency-Check. Set the NVD_API_KEY secret, or set ALLOW_TRIVY_FALLBACK=true to explicitly accept the Trivy fallback.'
            fi
        else
            fallback_allowed='true'
            reason='NVD_API_KEY is not set: a non-release branch uses the Trivy fallback; it is not equivalent to the primary NVD scan.'
        fi
        ;;
    *)
        fallback_allowed='false'
        reason="event '$event' is not supported by this SCA policy."
        ;;
esac

printf 'scanner=%s\n' "$scanner"
printf 'fallback_allowed=%s\n' "$fallback_allowed"
printf 'reason=%s\n' "$reason"
