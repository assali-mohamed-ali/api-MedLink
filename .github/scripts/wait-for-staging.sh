#!/usr/bin/env bash
set -euo pipefail

repository=''
workflow=''
jobs=()
branch='staging'
master='master'
timeout=1800
interval=30
token=''

usage() {
    cat >&2 <<'EOF'
usage: wait-for-staging.sh --repository <owner/repo> --workflow <file> [options]
  --job <name>       job to wait for; repeat to require several (default: deploy-staging)
  --branch <name>    staging branch (default: staging)
  --master <name>    branch that staging must contain (default: master)
  --timeout <seconds> overall timeout (default: 1800)
  --interval <seconds> poll interval (default: 30)
  --token <token>    optional read token for a private counterpart repository
EOF
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --repository) repository="${2:-}"; shift 2 ;;
        --workflow) workflow="${2:-}"; shift 2 ;;
        --job) [ -n "${2:-}" ] || usage; jobs+=("$2"); shift 2 ;;
        --branch) branch="${2:-}"; shift 2 ;;
        --master) master="${2:-}"; shift 2 ;;
        --timeout) timeout="${2:-}"; shift 2 ;;
        --interval) interval="${2:-}"; shift 2 ;;
        --token) token="${2:-}"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

[ -n "$repository" ] || usage
[ -n "$workflow" ] || usage
[ "${#jobs[@]}" -gt 0 ] || jobs=('deploy-staging')
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
    echo "::error::Invalid repository '$repository'; expected owner/name." >&2
    exit 2
}
[[ "$workflow" =~ ^[A-Za-z0-9_.-]+\.ya?ml$ ]] || {
    echo "::error::Invalid workflow filename '$workflow'." >&2
    exit 2
}
[[ "$branch" =~ ^[A-Za-z0-9._/-]+$ ]] && [[ "$master" =~ ^[A-Za-z0-9._/-]+$ ]] || {
    echo "::error::Invalid branch name." >&2
    exit 2
}
[[ "$timeout" =~ ^[0-9]+$ ]] && [[ "$interval" =~ ^[1-9][0-9]*$ ]] || {
    echo "::error::--timeout and --interval must be positive integers." >&2
    exit 2
}

api_base="${GITHUB_API_URL:-https://api.github.com}"
api_base="${api_base%/}"

api() {
    local path="$1"
    local -a options=(
        --fail --silent --show-error --retry 2 --retry-connrefused --max-time 20
        --header 'Accept: application/vnd.github+json'
        --header 'X-GitHub-Api-Version: 2022-11-28'
    )
    if [ -n "$token" ]; then
        options+=(--header "Authorization: Bearer $token")
    fi
    curl "${options[@]}" "${api_base}${path}"
}

failed_conclusion() {
    case "$1" in
        failure|cancelled|timed_out|action_required|startup_failure) return 0 ;;
        *) return 1 ;;
    esac
}

deadline=$(( $(date +%s) + timeout ))
state=''

while :; do
    if ! head_json="$(api "/repos/${repository}/commits/${branch}")"; then
        echo "::error::Cannot read ${repository}/${branch} from GitHub. Set CROSS_REPO_TOKEN for a private repository." >&2
        exit 1
    fi
    head_sha="$(jq -er '.sha' <<<"$head_json")" || {
        echo "::error::GitHub returned no commit SHA for ${repository}/${branch}." >&2
        exit 1
    }

    if ! compare_json="$(api "/repos/${repository}/compare/${master}...${branch}")"; then
        echo "::error::Cannot compare ${master}...${branch} in ${repository}." >&2
        exit 1
    fi
    compare_status="$(jq -r '.status // "unknown"' <<<"$compare_json")"

    if [ "$compare_status" != 'ahead' ] && [ "$compare_status" != 'identical' ]; then
        state="${repository}/${branch} does not contain ${master} yet (compare: ${compare_status})"
    else
        if ! runs_json="$(api "/repos/${repository}/actions/workflows/${workflow}/runs?branch=${branch}&per_page=100")"; then
            echo "::error::Cannot read the ${workflow} runs of ${repository}." >&2
            exit 1
        fi
        run_id="$(jq -r --arg sha "$head_sha" '
            [.workflow_runs[] | select(.head_sha == $sha and (.event == "push" or .event == "workflow_dispatch"))]
            | sort_by(.run_number) | last | .id // empty
        ' <<<"$runs_json")"

        if [ -z "$run_id" ]; then
            state="no ${workflow} push run for ${repository}@${head_sha} yet"
        else
            if ! jobs_json="$(api "/repos/${repository}/actions/runs/${run_id}/jobs?per_page=100")"; then
                echo "::error::Cannot read the jobs of ${repository} run ${run_id}." >&2
                exit 1
            fi
            all_success=1
            pending=''
            for job_name in "${jobs[@]}"; do
                job="$(jq -r --arg name "$job_name" '
                    [.jobs[] | select(.name == $name)] | last
                    | [(.status // ""), (.conclusion // ""), (.html_url // "")] | @tsv
                ' <<<"$jobs_json")"
                job_status="$(cut -f1 <<<"$job")"
                job_conclusion="$(cut -f2 <<<"$job")"
                job_url="$(cut -f3 <<<"$job")"

                if [ "$job_conclusion" = 'success' ]; then
                    continue
                fi
                all_success=0
                if failed_conclusion "$job_conclusion"; then
                    echo "::error::${repository}: ${job_name} ended with '${job_conclusion}' for ${head_sha} (${job_url})." >&2
                    exit 1
                fi
                if [ "$job_conclusion" = 'skipped' ] || [ "$job_conclusion" = 'neutral' ]; then
                    echo "::error::${repository}: ${job_name} was not run for ${head_sha} (${job_conclusion})." >&2
                    exit 1
                fi
                if [ -z "$job_status" ]; then
                    pending+="${job_name} has not started yet in ${repository} run ${run_id}; "
                else
                    pending+="${job_name} is ${job_status}${job_conclusion:+ (${job_conclusion})} in ${repository} run ${run_id}; "
                fi
            done
            if [ "$all_success" -eq 1 ]; then
                echo "${repository}: ${master} is in ${branch}, and ${jobs[*]} succeeded for ${head_sha}."
                exit 0
            fi
            state="${pending%; }"
        fi
    fi

    echo "Waiting: ${state}"
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "::error::Timed out after ${timeout}s waiting for ${repository} staging deployment: ${state}." >&2
        exit 1
    fi
    sleep "$interval"
done
