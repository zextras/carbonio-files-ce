#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
# SPDX-License-Identifier: AGPL-3.0-only
#
# API guard: keeps generated API contract files and API converters in step.
#
#   1. Converter check: when a contract file differs textually between the base and HEAD, at least
#      one `since = (ApiVersion.)?CURRENT` converter must exist in the sources at HEAD (an empty
#      @ApiConverter class is enough). A contract file that does not exist at the base (its first
#      introduction) is not a change. Lines of our contract that are explained by an embedded
#      dependency pin bump (see --embedded-*) are ignored.
#   2. Support window: prints ONE warning listing vX.Y.Z tags merged into the default branch that
#      are older than the window and still at or above the oldest supported version. Never fails.
#
# Runs in Jenkins (dt3_apiGuard) and as a local pre-commit hook (vendored as .ci/api-guard.sh).
# By default only the committed HEAD is compared, never the working tree. With --staged the INDEX
# is compared instead of HEAD (normal-stage pre-commit hook during `git commit`); with a clean
# index both modes agree.
#
# Usage: api-guard.sh [options]
#   --base <ref>                     Compare against this ref (default: merge-base with the base branch)
#   --base-branch <name>             Branch whose origin/<name> (or local <name>) gives the merge-base
#                                    (default: --default-branch)
#   --default-branch <name>          Default branch (default: main)
#   --contract <path>                Contract file, repeatable
#                                    (default: app/docs/contract/schema.graphql, app/docs/contract/openapi.json)
#   --sources <pathspec>             git pathspec of converter sources, repeatable
#                                    (default: Java files under any src/main/java)
#   --oldest-supported-file <file>   File holding the oldest supported version (enables the window warning)
#   --oldest-supported-key <key>     Key in that file (default: quarkus.carbonio-api-versioning.oldest-supported)
#   --window-months <n>              Support window length (default: 15)
#   --branch <name>                  Branch under test; release/* skips both checks (default: current branch)
#   --staged                         Compare the index instead of HEAD (contract files, converters, pom)
#   --skip-converter-check           Run only the window warning
#   --skip-window                    Run only the converter check
#   --pom-file <path>                POM holding the embedded pin property (default: pom.xml)
#   --embedded-pom-property <name>   Property pinning the embedded dependency version, repeatable with
#   --embedded-repo <url-or-path>    repository of the embedded dependency,
#   --embedded-tag-prefix <prefix>   tag prefix (default: v; a trailing -<digits> in the property is dropped),
#   --embedded-contract <theirs>=<ours>
#                                    contract file in the embedded repository and its counterpart here,
#                                    repeatable. The options are positional: each --embedded-pom-property
#                                    starts a new embedded dependency.
#   --now <epoch>                    Override the current time (tests)
#
# Exit codes:
#   0 ok (warnings allowed), 1 violation, 2 usage or environment error

set -euo pipefail

TAG="[api-guard]"
DEFAULT_BRANCH="main"
BASE=""
BASE_BRANCH=""
CONTRACTS=()
SOURCES=()
OLDEST_FILE=""
OLDEST_KEY="quarkus.carbonio-api-versioning.oldest-supported"
WINDOW_MONTHS=15
BRANCH=""
SKIP_CONVERTER=0
STAGED=0
NEW_REV="HEAD"
CACHED=()
SKIP_WINDOW=0
POM_FILE="pom.xml"
NOW=""
EMB_PROP=()
EMB_REPO=()
EMB_PREFIX=()
EMB_MAPS=()

die() {
    echo "$TAG ERROR: $*" >&2
    exit 2
}

need_value() {
    [ "$2" -ge 2 ] || die "option $1 requires a value"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-converter-check) SKIP_CONVERTER=1; shift; continue ;;
        --skip-window) SKIP_WINDOW=1; shift; continue ;;
        --staged) STAGED=1; shift; continue ;;
        -h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    esac
    need_value "$1" "$#"
    case "$1" in
        --base) BASE="$2" ;;
        --base-branch) BASE_BRANCH="$2" ;;
        --default-branch) DEFAULT_BRANCH="$2" ;;
        --contract) CONTRACTS+=("$2") ;;
        --sources) SOURCES+=("$2") ;;
        --oldest-supported-file) OLDEST_FILE="$2" ;;
        --oldest-supported-key) OLDEST_KEY="$2" ;;
        --window-months) WINDOW_MONTHS="$2" ;;
        --branch) BRANCH="$2" ;;
        --pom-file) POM_FILE="$2" ;;
        --now) NOW="$2" ;;
        --embedded-pom-property)
            EMB_PROP+=("$2"); EMB_REPO+=(""); EMB_PREFIX+=("v"); EMB_MAPS+=("") ;;
        --embedded-repo | --embedded-tag-prefix | --embedded-contract)
            [ "${#EMB_PROP[@]}" -gt 0 ] || die "$1 must follow --embedded-pom-property"
            last=$((${#EMB_PROP[@]} - 1))
            case "$1" in
                --embedded-repo) EMB_REPO[last]="$2" ;;
                --embedded-tag-prefix) EMB_PREFIX[last]="$2" ;;
                --embedded-contract)
                    case "$2" in *=*) ;; *) die "--embedded-contract expects <theirs>=<ours>" ;; esac
                    EMB_MAPS[last]="${EMB_MAPS[last]}$2"$'\n' ;;
            esac ;;
        *) die "unknown option: $1" ;;
    esac
    shift 2
done

case "$WINDOW_MONTHS" in '' | *[!0-9]*) die "--window-months must be a non-negative integer" ;; esac
[ -n "$NOW" ] || NOW=$(date -u +%s)

git rev-parse --git-dir > /dev/null 2>&1 || die "not inside a git working tree"
git rev-parse --verify -q HEAD > /dev/null || die "repository has no commits"

if [ "$STAGED" -eq 1 ]; then
    NEW_REV=""
    CACHED=(--cached)
fi

[ ${#CONTRACTS[@]} -gt 0 ] || CONTRACTS=(app/docs/contract/schema.graphql app/docs/contract/openapi.json)
[ ${#SOURCES[@]} -gt 0 ] || SOURCES=(':(glob)**/src/main/java/**/*.java')
[ -n "$BASE_BRANCH" ] || BASE_BRANCH="$DEFAULT_BRANCH"
[ -n "$BRANCH" ] || BRANCH=$(git rev-parse --abbrev-ref HEAD 2> /dev/null || true)

case "$BRANCH" in
    release/*)
        echo "$TAG Branch $BRANCH is a release branch: contract and window checks skipped"
        exit 0 ;;
esac

resolve_branch_ref() {
    local name="$1"
    if git rev-parse --verify -q "refs/remotes/origin/$name^{commit}" > /dev/null; then
        echo "origin/$name"
    elif git rev-parse --verify -q "refs/heads/$name^{commit}" > /dev/null; then
        echo "$name"
    fi
}

# ─── 1. converter check ──────────────────────────────────────────────────────

# changed_lines <base> <path>: signed changed lines of <path> between <base> and HEAD (or the index), commas stripped
changed_lines() {
    if [ "$STAGED" -eq 1 ]; then
        git diff --cached -U0 --no-color --no-ext-diff "$1" -- "$2"
    else
        git diff -U0 --no-color --no-ext-diff "$1" HEAD -- "$2"
    fi \
        | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' \
        | sed -E 's/,[[:space:]]*$//' || true
}

pom_property() {
    git show "$1:$POM_FILE" 2> /dev/null \
        | sed -n -E "s|.*<$2>[[:space:]]*([^<[:space:]]+)[[:space:]]*</$2>.*|\1|p" | head -n 1 || true
}

# embedded_ignored_file <base> <our-path> <tmpdir>: prints the path of a file with the embedded contract diff lines
embedded_ignored_file() {
    local base="$1" ours="$2" tmp="$3" i prop old new tagold tagnew repo prefix theirs
    local ce="$tmp/ce.diff"
    : > "$ce"
    local found=0
    for i in "${!EMB_PROP[@]}"; do
        theirs=""
        while IFS= read -r m; do
            [ -n "$m" ] || continue
            if [ "${m#*=}" = "$ours" ]; then theirs="${m%%=*}"; fi
        done <<< "${EMB_MAPS[$i]}"
        [ -n "$theirs" ] || continue
        prop="${EMB_PROP[$i]}"
        old=$(pom_property "$base" "$prop")
        new=$(pom_property "$NEW_REV" "$prop")
        if [ -z "$old" ] || [ -z "$new" ] || [ "$old" = "$new" ]; then continue; fi
        repo="${EMB_REPO[$i]}"
        prefix="${EMB_PREFIX[$i]}"
        [ -n "$repo" ] || { echo "$TAG WARNING: no --embedded-repo for $prop, pin bump not analysed" >&2; continue; }
        tagold="${prefix}$(echo "$old" | sed -E 's/-[0-9]+$//')"
        tagnew="${prefix}$(echo "$new" | sed -E 's/-[0-9]+$//')"
        local work="$tmp/emb$i"
        if [ ! -d "$work" ]; then
            mkdir -p "$work"
            if ! { git init -q "$work" \
                && git -C "$work" fetch -q --depth=1 "$repo" tag "$tagold" tag "$tagnew"; } > /dev/null 2>&1; then
                echo "$TAG WARNING: cannot fetch tags $tagold and $tagnew from $repo: pin bump of $prop not analysed" >&2
                continue
            fi
        fi
        echo "$TAG Pin $prop moved $old -> $new: comparing $theirs between $tagold and $tagnew" >&2
        git -C "$work" diff -U0 --no-color --no-ext-diff "refs/tags/$tagold" "refs/tags/$tagnew" -- "$theirs" \
            | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' | sed -E 's/,[[:space:]]*$//' >> "$ce" || true
        found=1
    done
    [ "$found" -eq 1 ] && echo "$ce"
    return 0
}

converter_check() {
    local base ref tmp changed=() path unexplained=0 ignored=0 ce

    if [ -n "$BASE" ]; then
        base=$(git rev-parse --verify -q "$BASE^{commit}") || die "--base $BASE is not a commit"
    else
        ref=$(resolve_branch_ref "$BASE_BRANCH")
        [ -n "$ref" ] || die "no origin/$BASE_BRANCH or $BASE_BRANCH to compare against (fetch it, or pass --base)"
        base=$(git merge-base HEAD "$ref" 2> /dev/null) \
            || die "no merge-base between HEAD and $ref (shallow clone? fetch more history)"
    fi
    echo "$TAG Comparing contract files against ${base:0:12}"

    tmp=$(mktemp -d)
    trap 'rm -rf "${tmp:?}"' RETURN

    for path in "${CONTRACTS[@]}"; do
        if ! git cat-file -e "$base:$path" 2> /dev/null; then
            if git cat-file -e "$NEW_REV:$path" 2> /dev/null; then
                echo "$TAG $path is new in this branch: first introduction is not a contract change"
            fi
            continue
        fi
        if [ "$STAGED" -eq 1 ]; then
            if git diff --cached --quiet "$base" -- "$path"; then continue; fi
        elif git diff --quiet "$base" HEAD -- "$path"; then
            continue
        fi
        ce=""
        if [ "${#EMB_PROP[@]}" -gt 0 ]; then
            ce=$(embedded_ignored_file "$base" "$path" "$tmp")
        fi
        if [ -n "$ce" ]; then
            read -r unexplained ignored < <(changed_lines "$base" "$path" | awk -v ce="$ce" '
                BEGIN { while ((getline l < ce) > 0) n[l]++ }
                { if (n[$0] > 0) { n[$0]--; ig++ } else { un++; print "[api-guard]   unexplained: " $0 > "/dev/stderr" } }
                END { print un + 0, ig + 0 }')
            if [ "$unexplained" -eq 0 ]; then
                echo "$TAG $path: all $ignored changed line(s) are explained by the embedded pin bump: ignored"
                continue
            fi
            echo "$TAG $path: $ignored changed line(s) explained by the embedded pin bump, $unexplained not"
        fi
        changed+=("$path")
    done

    if [ ${#changed[@]} -eq 0 ]; then
        echo "$TAG No contract change needing a converter"
        return 0
    fi

    local rev=()
    [ "$STAGED" -eq 1 ] || rev=(HEAD)
    if git grep "${CACHED[@]}" -q -E 'since[[:space:]]*=[[:space:]]*(ApiVersion\.)?CURRENT([^A-Za-z0-9_]|$)' "${rev[@]}" -- "${SOURCES[@]}"; then
        echo "$TAG Contract changed (${changed[*]}) and a CURRENT converter exists"
        return 0
    fi

    echo ""
    echo "$TAG FATAL: contract files changed but no converter with since = CURRENT exists:"
    for path in "${changed[@]}"; do
        echo "$TAG   changed: $path"
    done
    echo "$TAG Fix: add a class annotated @ApiConverter(since = ApiVersion.CURRENT) (it may be empty, to document the change)."
    return 1
}

# ─── 2. window warning ───────────────────────────────────────────────────────

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
    ref=$(resolve_branch_ref "$DEFAULT_BRANCH")
    if [ -z "$ref" ]; then
        echo "$TAG WARNING: no origin/$DEFAULT_BRANCH or $DEFAULT_BRANCH, support window not checked"
        return 0
    fi

    cutoff=$(echo "$NOW" | awk -v months="$WINDOW_MONTHS" '
        function dim(y, m) { return (m == 2) ? ((y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) ? 29 : 28) : ((m == 4 || m == 6 || m == 9 || m == 11) ? 30 : 31) }
        function days(y, m, d,    era, yoe, doy, doe) {
            y -= (m <= 2); era = int((y >= 0 ? y : y - 399) / 400); yoe = y - era * 400
            doy = int((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1
            doe = yoe * 365 + int(yoe / 4) - int(yoe / 100) + doy
            return era * 146097 + doe - 719468
        }
        function civil(z,    era, doe, yoe, y, doy, mp) {
            z += 719468; era = int((z >= 0 ? z : z - 146096) / 146097); doe = z - era * 146097
            yoe = int((doe - int(doe / 1460) + int(doe / 36524) - int(doe / 146096)) / 365)
            y = yoe + era * 400; doy = doe - (365 * yoe + int(yoe / 4) - int(yoe / 100))
            mp = int((5 * doy + 2) / 153); CD = doy - int((153 * mp + 2) / 5) + 1
            CM = mp + (mp < 10 ? 3 : -9); CY = y + (CM <= 2)
        }
        { t = $1; secs = t % 86400; civil(int(t / 86400))
          m = CM - months; y = CY; while (m < 1) { m += 12; y-- }
          d = CD; if (d > dim(y, m)) d = dim(y, m)
          print days(y, m, d) * 86400 + secs }')

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

status=0
if [ "$SKIP_CONVERTER" -eq 0 ]; then
    converter_check || status=$?
fi
if [ "$SKIP_WINDOW" -eq 0 ]; then
    window_warning
fi
exit "$status"
