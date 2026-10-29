#!/usr/bin/env bash
# Decide which SCA scanner this run may use, and whether the fallback is allowed.
# Pull requests use Trivy so they do not block for the first-time NVD database
# download. OWASP Dependency-Check remains the primary scanner for keyed
# branch pushes, scheduled scans, and manual full scans.
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

# PRs must get fast, actionable dependency feedback. Dependency-Check's NVD
# database bootstrap/update can consume most of the 60-minute job timeout.
# Trivy scans the packaged application and gates fixed CRITICAL/HIGH findings.
if [ "$event" = "pull_request" ]; then
    scanner='trivy'
    fallback_allowed='true'
    reason='Pull requests use the fast Trivy package scan; OWASP Dependency-Check runs on branch pushes and scheduled/manual full scans.'
elif truthy "$has_key"; then
    scanner='dependency-check'
    fallback_allowed='true'
    reason='NVD_API_KEY is set: OWASP Dependency-Check (NVD) is the primary scanner for this full scan.'
else
    case "$event" in
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
            reason="NVD_API_KEY is not set and event '$event' is not allowed to use the Trivy fallback."
            ;;
    esac
fi

printf 'scanner=%s\n' "$scanner"
printf 'fallback_allowed=%s\n' "$fallback_allowed"
printf 'reason=%s\n' "$reason"
