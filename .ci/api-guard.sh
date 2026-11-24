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
#      Consequences of counting: reverting the change of an unstamped base entry E and replacing E's
#      line with a new one leaves the count unchanged and fails; keep E's line and add another entry
#      (for example a description-only one). Moving the registry file counts zero at the base, so a
#      PR that moves it passes the count: rename only when the base holds no unstamped CURRENT entry,
#      or add an entry in the same PR.
#      Release branches (release/*, hotfix lines) get the same rule as main: nothing is skipped.
#   2. Main and release builds (--main-build): contract changed in HEAD^1..HEAD and no CURRENT entry
#      at HEAD, reported as a warning, never a failure.
#   3. Converter rule (needs --oldest-supported-file): an @ApiChange that binds a converter
#      (converter = X.class or inherits = X.class) with a stamped since "S" at the base must still exist
#      at HEAD (or in the index with --staged) with the same converter class (simple name, so Foo.class
#      and com.x.Foo.class match) and the same since, as long as S is above the oldest-supported version
#      O read on the compared side: clients from O up to S still need that converter. A since edited on
#      a line counts as the removal of the old line. Lines whose since is CURRENT at the base,
#      description-only lines and a registry missing at the base are ignored. Raise oldest-supported to
#      at least S in the same change, or keep the converter and its line. Runs in every mode, whether
#      or not the contract changed, after rule 1, and both violations are reported. With --main-build
#      (base HEAD^1) it is a warning. Without --oldest-supported-file, or when the key is not a X.Y.Z
#      value on the compared side, it prints one "skipped" line and does not fail.
#   4. Expired converter rule (needs --oldest-supported-file): the mirror of rule 3. An @ApiChange at
#      HEAD (or in the index with --staged) that binds a converter (converter = X.class or inherits =
#      X.class) with a stamped since "S" at or below the oldest-supported version O read on the same side
#      is disabled in every build and serves no supported client: delete it with its converter, its tests
#      and the legacy types only it uses. Needs no base, so it also runs on a main build without a parent.
#      CURRENT and description-only lines are ignored. Same levels and skip behaviour as rule 3.
#   5. Support window (--window, requires --oldest-supported-file): prints ONE warning listing vX.Y.Z tags
#      merged into the base branch (--base-branch, default main) that are older than the window and
#      still at or above the oldest supported version. Computed only with --window. Never fails.
#
# Runs in Jenkins (dt3_apiGuard) and as a local pre-commit hook (vendored as .ci/api-guard.sh).
# By default the committed HEAD is compared, never the working tree. With --staged the INDEX is
# compared instead of HEAD (normal-stage pre-commit hook during `git commit`).
#
# Usage: api-guard.sh [options]
#   --registry <path>                Registry file holding the @ApiChange lines, repeatable (required)
#   --contract <path>                Contract file, repeatable
#                                    (default: app/docs/schema.graphql, app/docs/openapi.json)
#   --base-branch <name>             Branch whose origin/<name> (or local <name>) gives the merge-base and
#                                    the tags of the window. Without it, the base is detected: the
#                                    candidates are main and every release/* branch (origin/<name>, else
#                                    the local branch) and the one whose merge-base with HEAD is nearest
#                                    wins (a descendant of, or equal to, the merge-bases of the others;
#                                    ties go to main). The window then uses main.
#   --staged                         Compare the index instead of HEAD
#   --main-build                     Compare HEAD^1..HEAD and only warn (builds of the default branch and
#                                    of release/* branches)
#   --oldest-supported-file <file>   File holding quarkus.carbonio-api-versioning.oldest-supported; feeds
#                                    the converter and expired converter rules (read from HEAD, or the
#                                    index with --staged) and the window warning
#   --window                         Compute the support window warning (requires --oldest-supported-file)
#   --window-months <n>              Support window length (default: 15)
#   --now <epoch>                    Override the current time (tests)
#
# Exit codes:
#   0 ok (warnings allowed), 1 rule violation, 2 usage or unexpected error

set -Eeuo pipefail

TAG="[api-guard]"
BASE_BRANCH="main"
BASE_EXPLICIT=0
CONTRACTS=()
REGISTRIES=()
OLDEST_FILE=""
WINDOW=0
OLDEST_KEY="quarkus.carbonio-api-versioning.oldest-supported"
WINDOW_MONTHS=15
STAGED=0
MAIN_BUILD=0
NEW_REV="HEAD"
NOW=""
VIOLATION=0
COMPARE_BASE=""
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
        --window) WINDOW=1; shift; continue ;;
        -h | --help) sed -n '2,/^set -Eeuo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    esac
    need_value "$1" "$#"
    case "$1" in
        --base-branch) BASE_BRANCH="$2"; BASE_EXPLICIT=1 ;;
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
[ "$WINDOW" -eq 0 ] || [ -n "$OLDEST_FILE" ] || die "--window requires --oldest-supported-file"
[ "${#REGISTRIES[@]}" -gt 0 ] || die "--registry is required (path of the file holding the @ApiChange lines)"

git rev-parse --git-dir > /dev/null 2>&1 || die "not inside a git working tree"
git rev-parse --verify -q HEAD > /dev/null || die "repository has no commits"

if [ "$STAGED" -eq 1 ]; then
    NEW_REV=""
fi

if [ "${#CONTRACTS[@]}" -eq 0 ]; then
    CONTRACTS=(app/docs/schema.graphql app/docs/openapi.json)
fi
resolve_branch_ref() {
    local name="$1"
    if git rev-parse --verify -q "refs/remotes/origin/$name^{commit}" > /dev/null; then
        echo "origin/$name"
    elif git rev-parse --verify -q "refs/heads/$name^{commit}" > /dev/null; then
        echo "$name"
    fi
}

# merge_base_with <ref>: merge-base of HEAD (and of the merge being committed, if any) with <ref>
merge_base_with() {
    local ref="$1" merge_head=""
    if [ "$STAGED" -eq 1 ]; then
        merge_head=$(git rev-parse -q --verify MERGE_HEAD 2> /dev/null || true)
    fi
    if [ -n "$merge_head" ]; then
        git merge-base "$ref" HEAD "$merge_head" 2> /dev/null
    else
        git merge-base HEAD "$ref" 2> /dev/null
    fi
}

# detect_base: sets BASE_REF and BASE_SHA to the nearest merge-base among main and the release/* branches
detect_base() {
    local name ref sha count best_count
    local names=()
    BASE_REF=""
    BASE_SHA=""
    best_count=0
    mapfile -t names < <({
        echo main
        git for-each-ref --format='%(refname)' refs/remotes/origin/release refs/heads/release \
            | sed -E 's#^refs/remotes/origin/##; s#^refs/heads/##' | LC_ALL=C sort -u
    } | awk '!seen[$0]++')
    for name in "${names[@]}"; do
        ref=$(resolve_branch_ref "$name")
        [ -n "$ref" ] || continue
        sha=$(merge_base_with "$ref") || continue
        if [ -z "$BASE_SHA" ]; then
            BASE_REF="$ref"; BASE_SHA="$sha"
            best_count=$(git rev-list --count "$sha")
        elif [ "$sha" != "$BASE_SHA" ]; then
            if git merge-base --is-ancestor "$BASE_SHA" "$sha"; then
                BASE_REF="$ref"; BASE_SHA="$sha"
                best_count=$(git rev-list --count "$sha")
            elif ! git merge-base --is-ancestor "$sha" "$BASE_SHA"; then
                count=$(git rev-list --count "$sha")
                if [ "$count" -gt "$best_count" ]; then
                    BASE_REF="$ref"; BASE_SHA="$sha"; best_count="$count"
                fi
            fi
        fi
    done
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
        echo "$TAG WARNING: the last commit on the default or release branch changed contract files and the registry has no @ApiChange with since = CURRENT:"
    else
        echo "$TAG FATAL: contract files changed but the registry has no new @ApiChange entry with since = CURRENT for this version:"
    fi
    if [ "$before" -gt 0 ]; then
        echo "$TAG   the base already holds $before unstamped CURRENT entries of other changes: add your own"
        echo "$TAG   reverting or editing the line of a base entry does not count: keep that line and ADD a new entry (for example a description-only one)"
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
    local base ref path reg before
    local changed=()

    if [ "$MAIN_BUILD" -eq 1 ]; then
        if ! base=$(git rev-parse --verify -q "HEAD^1^{commit}"); then
            echo "$TAG Main build: HEAD has no parent, nothing to compare"
            return 0
        fi
    else
        if [ "$BASE_EXPLICIT" -eq 1 ]; then
            ref=$(resolve_branch_ref "$BASE_BRANCH")
            [ -n "$ref" ] || die "no origin/$BASE_BRANCH or $BASE_BRANCH to compare against (fetch it)"
            base=$(merge_base_with "$ref") \
                || die "no merge-base between HEAD and $ref (shallow clone? fetch more history)"
        else
            detect_base
            [ -n "$BASE_SHA" ] || die "no origin/$BASE_BRANCH or $BASE_BRANCH to compare against (fetch it)"
            ref="$BASE_REF"
            base="$BASE_SHA"
        fi
    fi
    COMPARE_BASE="$base"
    if [ "$MAIN_BUILD" -eq 1 ]; then
        echo "$TAG Comparing contract files against ${base:0:12}"
    else
        echo "$TAG Comparing contract files against ${base:0:12} (merge-base with $ref)"
    fi

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

# oldest_at <rev>: sets OLDEST_RAW and OLDEST_PARTS from the oldest-supported key of $OLDEST_FILE at <rev>
# ("" = the index); returns 1 and sets SKIP_REASON when it cannot be read as X.Y.Z
oldest_at() {
    local text
    OLDEST_RAW=""
    OLDEST_PARTS=""
    if ! text=$(git show "$1:$OLDEST_FILE" 2> /dev/null); then
        SKIP_REASON="$OLDEST_FILE not found at ${1:-the index}"
        return 1
    fi
    OLDEST_RAW=$(printf '%s\n' "$text" | sed -n -E "s/^[[:space:]]*${OLDEST_KEY//./\\.}[[:space:]]*[=:][[:space:]]*([^[:space:]#]+).*/\1/p" | head -n 1)
    OLDEST_PARTS=$(semver_parts "$OLDEST_RAW")
    if [ -z "$OLDEST_PARTS" ]; then
        SKIP_REASON="key $OLDEST_KEY is not a X.Y.Z version in $OLDEST_FILE at ${1:-the index} (found '${OLDEST_RAW}')"
        return 1
    fi
}

# semver_gt <a> <b>: succeeds when X.Y.Z <a> is greater than <b>, compared numerically per part
semver_gt() {
    local a1 a2 a3 b1 b2 b3
    read -r a1 a2 a3 <<< "$(semver_parts "$1")"
    read -r b1 b2 b3 <<< "$(semver_parts "$2")"
    if [ $((10#$a1)) -ne $((10#$b1)) ]; then [ $((10#$a1)) -gt $((10#$b1)) ]; return; fi
    if [ $((10#$a2)) -ne $((10#$b2)) ]; then [ $((10#$a2)) -gt $((10#$b2)) ]; return; fi
    [ $((10#$a3)) -gt $((10#$b3)) ]
}

# converter_pairs <rev>: one "<ConverterSimpleName> <since>" line per converter binding (converter = X.class or
# inherits = X.class) of an @ApiChange with a stamped since "X.Y.Z" in the registry file(s) at <rev> ("" = the
# index; a registry missing at <rev> contributes nothing); comments are ignored like in count_current and
# parameters are split outside string literals and nested parentheses
converter_pairs() {
    local reg
    for reg in "${REGISTRIES[@]}"; do
        git rev-parse -q --verify "$1:$reg" > /dev/null 2>&1 || continue
        git show "$1:$reg" \
            | { grep -vE '^[[:space:]]*(//|/\*|\*)' || true; } | sed -E 's#(^|[[:space:]])//.*$##' | tr '\n' ' ' \
            | awk '
                function trim(t) { gsub(/^[ \t\r]+|[ \t\r]+$/, "", t); return t }
                function emit(   k, part, since, names, nn, v, j, nm) {
                    since = ""; nn = 0
                    for (k = 1; k <= nparts; k++) {
                        part = trim(parts[k])
                        if (part ~ /^since[ \t]*=[ \t]*"[0-9]+\.[0-9]+\.[0-9]+"$/) {
                            since = part; sub(/^[^"]*"/, "", since); sub(/"$/, "", since)
                        } else if (part ~ /^(converter|inherits)[ \t]*=[ \t]*[A-Za-z_$][A-Za-z0-9_$. \t]*\.[ \t]*class$/) {
                            v = part; sub(/^[^=]*=/, "", v); gsub(/[ \t]/, "", v); sub(/\.class$/, "", v)
                            nm = v; sub(/^.*\./, "", nm)
                            names[++nn] = nm
                        }
                    }
                    if (since != "") for (j = 1; j <= nn; j++) print names[j], since
                }
                {
                    s = $0; n = length(s); i = 1
                    while ((p = index(substr(s, i), "@ApiChange")) > 0) {
                        i += p - 1 + 10
                        while (substr(s, i, 1) ~ /[ \t]/) i++
                        if (substr(s, i, 1) != "(") continue
                        i++
                        depth = 0; instr = 0; part = ""; nparts = 0
                        for (; i <= n; i++) {
                            c = substr(s, i, 1)
                            if (instr) {
                                part = part c
                                if (c == "\\") { i++; part = part substr(s, i, 1) } else if (c == "\"") instr = 0
                                continue
                            }
                            if (c == "\"") { instr = 1; part = part c; continue }
                            if (c == "(" || c == "{") depth++
                            else if (c == ")" || c == "}") { if (depth == 0) break; depth-- }
                            if (c == "," && depth == 0) { parts[++nparts] = part; part = ""; continue }
                            part = part c
                        }
                        parts[++nparts] = part
                        emit()
                        i++
                    }
                }'
    done | LC_ALL=C sort -u
}

converter_check() {
    local base_pairs new_pairs name since level reg
    local found=0
    if [ -z "$OLDEST_FILE" ]; then
        echo "$TAG Converter removal check skipped: --oldest-supported-file not given"
        return 0
    fi
    if [ -z "$COMPARE_BASE" ]; then
        return 0
    fi
    if ! oldest_at "$NEW_REV"; then
        echo "$TAG Converter removal check skipped: $SKIP_REASON"
        return 0
    fi
    base_pairs=$(converter_pairs "$COMPARE_BASE")
    new_pairs=$(converter_pairs "$NEW_REV")
    level="FATAL"
    [ "$MAIN_BUILD" -eq 0 ] || level="WARNING"
    while read -r name since; do
        [ -n "$name" ] || continue
        if printf '%s\n' "$new_pairs" | grep -qxF "$name $since"; then
            continue
        fi
        semver_gt "$since" "$OLDEST_RAW" || continue
        if [ "$found" -eq 0 ]; then
            echo ""
        fi
        found=1
        echo "$TAG $level: converter $name (since $since) was removed or its since changed, but $since is above oldest-supported $OLDEST_RAW: clients from $OLDEST_RAW up to $since still need it."
    done <<< "$base_pairs"
    if [ "$found" -eq 0 ]; then
        echo "$TAG No active converter was removed"
        return 0
    fi
    for reg in "${REGISTRIES[@]}"; do
        echo "$TAG   registry file: $reg"
    done
    echo "$TAG Fix: raise $OLDEST_KEY to at least the since above in $OLDEST_FILE in the same change, or keep the converter and its @ApiChange line"
    if [ "$MAIN_BUILD" -eq 0 ]; then
        echo "$TAG Local bypass (hook only; CI still checks): SKIP=api-guard git commit ..."
        VIOLATION=1
    fi
    return 0
}

expired_check() {
    local name since level reg
    local found=0
    if [ -z "$OLDEST_FILE" ]; then
        echo "$TAG Expired converter check skipped: --oldest-supported-file not given"
        return 0
    fi
    if ! oldest_at "$NEW_REV"; then
        echo "$TAG Expired converter check skipped: $SKIP_REASON"
        return 0
    fi
    level="FATAL"
    [ "$MAIN_BUILD" -eq 0 ] || level="WARNING"
    while read -r name since; do
        [ -n "$name" ] || continue
        semver_gt "$since" "$OLDEST_RAW" && continue
        if [ "$found" -eq 0 ]; then
            echo ""
        fi
        found=1
        echo "$TAG $level: converter $name (since $since) is at or below oldest-supported $OLDEST_RAW: no supported client needs it and every build disables it."
    done <<< "$(converter_pairs "$NEW_REV")"
    if [ "$found" -eq 0 ]; then
        echo "$TAG No expired converter in the registry"
        return 0
    fi
    for reg in "${REGISTRIES[@]}"; do
        echo "$TAG   registry file: $reg"
    done
    echo "$TAG Fix: delete each converter above with its @ApiChange line, its @ApiVersionBefore tests and the legacy types only it uses (an inherits line: delete the line)"
    if [ "$MAIN_BUILD" -eq 0 ]; then
        echo "$TAG Local bypass (hook only; CI still checks): SKIP=api-guard git commit ..."
        VIOLATION=1
    fi
    return 0
}

window_warning() {
    local raw parts ref cutoff old_tags
    [ "$WINDOW" -eq 1 ] || return 0
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
converter_check
expired_check
window_warning
exit "$VIOLATION"
