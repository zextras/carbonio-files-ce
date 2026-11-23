#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
# SPDX-License-Identifier: AGPL-3.0-only
#
# API guard: a change of the generated API contract files must come with a new API change entry.
#
#   1. Registry rule: when a contract file differs between the base and HEAD, the registry file(s)
#      (--registry) at HEAD must hold more @ApiChange entries whose since is ApiVersion.CURRENT,
#      CURRENT or "current" than at the base (the annotation may span lines; `//` comment lines are
#      ignored). A contract file that does not exist at the base (its first introduction) is not a
#      change; a deleted one is.
#   2. Main builds (--main-build): contract changed in HEAD^1..HEAD and no CURRENT entry at HEAD,
#      reported as a warning, never a failure.
#   3. Support window (--oldest-supported-file): prints ONE warning listing vX.Y.Z tags merged into
#      the base branch that are older than the window and still at or above the oldest supported
#      version. Computed only when --oldest-supported-file is passed. Never fails.
#
# Runs in Jenkins (dt3_apiGuard) and as a local pre-commit hook (vendored as .ci/api-guard.sh).
# By default the committed HEAD is compared, never the working tree. With --staged the INDEX is
# compared instead of HEAD (normal-stage pre-commit hook during `git commit`).
#
# Usage: api-guard.sh [options]
#   --registry <path>                Registry file holding the @ApiChange lines, repeatable (required)
#   --contract <path>                Contract file, repeatable
#                                    (default: app/docs/schema.graphql, app/docs/openapi.json)
#   --base-branch <name>             Branch whose origin/<name> (or local <name>) gives the merge-base and,
#                                    on main builds, the tags of the window (default: main); release/*
#                                    (or a current branch release/*) skips every check
#   --staged                         Compare the index instead of HEAD
#   --main-build                     Compare HEAD^1..HEAD and only warn (builds of the default branch)
#   --oldest-supported-file <file>   File holding quarkus.carbonio-api-versioning.oldest-supported; enables
#                                    the window warning
#   --window-months <n>              Support window length (default: 15)
#   --now <epoch>                    Override the current time (tests)
#
# Exit codes:
#   0 ok (warnings allowed), 1 rule violation, 2 usage or unexpected error

set -Eeuo pipefail

TAG="[api-guard]"
BASE_BRANCH="main"
CONTRACTS=()
REGISTRIES=()
OLDEST_FILE=""
OLDEST_KEY="quarkus.carbonio-api-versioning.oldest-supported"
WINDOW_MONTHS=15
STAGED=0
MAIN_BUILD=0
NEW_REV="HEAD"
NOW=""
VIOLATION=0
rc=0
SECONDS_PER_MONTH=2629746

trap 'rc=$?; echo "$TAG ERROR: unexpected failure (exit $rc) at line $LINENO" >&2; exit 2' ERR

die() {
    echo "$TAG ERROR: $*" >&2
    exit 2
}

need_value() {
    [ "$2" -ge 2 ] || die "option $1 requires a value"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --staged) STAGED=1; shift; continue ;;
        --main-build) MAIN_BUILD=1; shift; continue ;;
        -h | --help) sed -n '2,/^set -Eeuo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    esac
    need_value "$1" "$#"
    case "$1" in
        --base-branch) BASE_BRANCH="$2" ;;
        --contract) CONTRACTS+=("$2") ;;
        --registry) REGISTRIES+=("$2") ;;
        --oldest-supported-file) OLDEST_FILE="$2" ;;
        --window-months) WINDOW_MONTHS="$2" ;;
        --now) NOW="$2" ;;
        *) die "unknown option: $1" ;;
    esac
    shift 2
done

case "$WINDOW_MONTHS" in '' | *[!0-9]*) die "--window-months must be a non-negative integer" ;; esac
if [ -n "$NOW" ]; then
    case "$NOW" in *[!0-9]*) die "--now must be an epoch in seconds" ;; esac
else
    NOW=$(date -u +%s)
fi
[ "$STAGED" -eq 0 ] || [ "$MAIN_BUILD" -eq 0 ] || die "--staged and --main-build cannot be combined"
[ "${#REGISTRIES[@]}" -gt 0 ] || die "--registry is required (path of the file holding the @ApiChange lines)"

git rev-parse --git-dir > /dev/null 2>&1 || die "not inside a git working tree"
git rev-parse --verify -q HEAD > /dev/null || die "repository has no commits"

if [ "$STAGED" -eq 1 ]; then
    NEW_REV=""
fi

if [ "${#CONTRACTS[@]}" -eq 0 ]; then
    CONTRACTS=(app/docs/schema.graphql app/docs/openapi.json)
fi
for branch in "$BASE_BRANCH" "$(git rev-parse --abbrev-ref HEAD 2> /dev/null || true)"; do
    case "$branch" in
        release/*)
            echo "$TAG Branch $branch is a release branch: all checks skipped"
            exit 0 ;;
    esac
done

resolve_branch_ref() {
    local name="$1"
    if git rev-parse --verify -q "refs/remotes/origin/$name^{commit}" > /dev/null; then
        echo "origin/$name"
    elif git rev-parse --verify -q "refs/heads/$name^{commit}" > /dev/null; then
        echo "$name"
    fi
}

# diff_quiet <base> <path>: succeeds when <path> is identical at <base> and at the compared point
diff_quiet() {
    if [ "$STAGED" -eq 1 ]; then
        git diff --cached --quiet "$1" -- "$2"
    else
        git diff --quiet "$1" HEAD -- "$2"
    fi
}

# count_current <rev>: number of @ApiChange entries with since = ApiVersion.CURRENT, CURRENT or "current" in the
# registry file(s) at <rev> ("" = the index; a registry missing at <rev> counts 0); comments are ignored and
# since must follow a `(` or `,`, so the word inside a string does not count
count_current() {
    local reg n=0 c
    for reg in "${REGISTRIES[@]}"; do
        git rev-parse -q --verify "$1:$reg" > /dev/null 2>&1 || continue
        c=$(git show "$1:$reg" \
            | { grep -vE '^[[:space:]]*(//|/\*|\*)' || true; } | sed -E 's#(^|[[:space:]])//.*$##' | tr '\n' ' ' \
            | { grep -oE '[(,][[:space:]]*since[[:space:]]*=[[:space:]]*(([A-Za-z_$][A-Za-z0-9_$]*\.)*ApiVersion\.CURRENT\b|CURRENT\b|"current")' || true; } | wc -l)
        n=$((n + c))
    done
    echo "$n"
}

report_violation() {
    local before="$1" path reg
    shift
    local changed=("$@")
    echo ""
    if [ "$MAIN_BUILD" -eq 1 ]; then
        echo "$TAG WARNING: the last commit on the default branch changed contract files and the registry has no @ApiChange with since = CURRENT:"
    else
        echo "$TAG FATAL: contract files changed but the registry has no new @ApiChange entry with since = CURRENT for this version:"
    fi
    if [ "$before" -gt 0 ]; then
        echo "$TAG   the base already holds $before unstamped CURRENT entries of other changes: add your own"
    fi
    for path in "${changed[@]}"; do
        echo "$TAG   changed contract file: $path"
    done
    for reg in "${REGISTRIES[@]}"; do
        echo "$TAG   registry file: $reg"
    done
    echo "$TAG Fix: add one line to the registry, for example"
    echo "$TAG   @ApiChange(since = ApiVersion.CURRENT, description = \"what changed in the API\")"
    echo "$TAG   (or converter = <YourConverter>.class when old clients need their values converted)"
    if [ "$MAIN_BUILD" -eq 0 ]; then
        echo "$TAG Local bypass (hook only; CI still checks): SKIP=api-guard git commit ..."
    fi
}

contract_check() {
    local base ref path reg merge_head before
    local changed=()

    if [ "$MAIN_BUILD" -eq 1 ]; then
        if ! base=$(git rev-parse --verify -q "HEAD^1^{commit}"); then
            echo "$TAG Main build: HEAD has no parent, nothing to compare"
            return 0
        fi
    else
        ref=$(resolve_branch_ref "$BASE_BRANCH")
        [ -n "$ref" ] || die "no origin/$BASE_BRANCH or $BASE_BRANCH to compare against (fetch it)"
        merge_head=""
        if [ "$STAGED" -eq 1 ]; then
            merge_head=$(git rev-parse -q --verify MERGE_HEAD 2> /dev/null || true)
        fi
        if [ -n "$merge_head" ]; then
            base=$(git merge-base "$ref" HEAD "$merge_head" 2> /dev/null) \
                || die "no merge-base between $ref and the merge being committed"
        else
            base=$(git merge-base HEAD "$ref" 2> /dev/null) \
                || die "no merge-base between HEAD and $ref (shallow clone? fetch more history)"
        fi
    fi
    echo "$TAG Comparing contract files against ${base:0:12}"

    for reg in "${REGISTRIES[@]}"; do
        if ! git rev-parse -q --verify "$NEW_REV:$reg" > /dev/null 2>&1; then
            die "registry file $reg does not exist at ${NEW_REV:-the index}"
        fi
    done

    for path in "${CONTRACTS[@]}"; do
        if ! git rev-parse -q --verify "$base:$path" > /dev/null 2>&1; then
            if git rev-parse -q --verify "$NEW_REV:$path" > /dev/null 2>&1; then
                echo "$TAG $path is new in this branch: first introduction is not a contract change"
            fi
            continue
        fi
        if diff_quiet "$base" "$path"; then
            continue
        fi
        changed+=("$path")
    done

    if [ "${#changed[@]}" -eq 0 ]; then
        echo "$TAG No contract change needing an @ApiChange line"
        return 0
    fi

    before=0
    [ "$MAIN_BUILD" -eq 1 ] || before=$(count_current "$base")
    if [ "$(count_current "$NEW_REV")" -gt "$before" ]; then
        if [ "$MAIN_BUILD" -eq 1 ]; then
            echo "$TAG Contract changed (${changed[*]}) and the registry has an @ApiChange entry with since = CURRENT"
        else
            echo "$TAG Contract changed (${changed[*]}) and the registry has a new @ApiChange entry with since = CURRENT"
        fi
        return 0
    fi

    report_violation "$before" "${changed[@]}"
    if [ "$MAIN_BUILD" -eq 0 ]; then
        VIOLATION=1
    fi
    return 0
}

semver_parts() {
    printf '%s\n' "$1" | sed -n -E 's/^v?([0-9]+)\.([0-9]+)\.([0-9]+).*/\1 \2 \3/p'
}

window_warning() {
    local raw parts ref cutoff old_tags
    [ -n "$OLDEST_FILE" ] || return 0
    if [ ! -f "$OLDEST_FILE" ]; then
        echo "$TAG WARNING: $OLDEST_FILE not found, support window not checked"
        return 0
    fi
    raw=$(sed -n -E "s/^[[:space:]]*${OLDEST_KEY//./\\.}[[:space:]]*[=:][[:space:]]*([^[:space:]#]+).*/\1/p" "$OLDEST_FILE" | head -n 1)
    parts=$(semver_parts "$raw")
    if [ -z "$parts" ]; then
        echo "$TAG WARNING: key $OLDEST_KEY not set to a X.Y.Z version in $OLDEST_FILE (found '${raw}'), support window not checked"
        return 0
    fi
    ref=$(resolve_branch_ref "$BASE_BRANCH")
    if [ -z "$ref" ]; then
        echo "$TAG WARNING: no origin/$BASE_BRANCH or $BASE_BRANCH, support window not checked"
        return 0
    fi

    cutoff=$((NOW - WINDOW_MONTHS * SECONDS_PER_MONTH))

    old_tags=$(git for-each-ref --merged "$ref" --format='%(refname:short) %(creatordate:unix)' 'refs/tags/v*' \
        | awk -v cutoff="$cutoff" -v floor="$parts" '
            BEGIN { split(floor, f, " ") }
            $1 ~ /^v[0-9]+\.[0-9]+\.[0-9]+$/ && $2 < cutoff {
                v = substr($1, 2); split(v, p, ".")
                if (p[1] + 0 > f[1] + 0 || (p[1] + 0 == f[1] + 0 && (p[2] + 0 > f[2] + 0 || (p[2] + 0 == f[2] + 0 && p[3] + 0 >= f[3] + 0))))
                    print p[1], p[2], p[3], $1
            }' \
        | sort -k1,1n -k2,2n -k3,3n | awk '{ print $4 }' | paste -sd, - | sed 's/,/, /g')

    if [ -n "$old_tags" ]; then
        echo "$TAG WARNING: released more than $WINDOW_MONTHS months ago and still supported: $old_tags. Raise $OLDEST_KEY in $OLDEST_FILE to drop them from the support window."
    else
        echo "$TAG Support window: no supported tag is older than $WINDOW_MONTHS months"
    fi
}

contract_check
window_warning
exit "$VIOLATION"
