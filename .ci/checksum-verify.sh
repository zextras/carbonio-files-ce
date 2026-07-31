#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only
#
# Verifies (autofixes) sha256sums=() against source=() in a PKGBUILD. Exit 0 ok, 1 error, 2 fixed.

set -euo pipefail

PKGBUILD_PATH="${1:?Usage: checksum-verify.sh <PKGBUILD> [true|false]}"
AUTOFIX="${2:-true}"
PKGDIR="$(dirname "$PKGBUILD_PATH")"

# Subshell sourcing expands ${pkgver} like makepkg; no source/sha256sums locals here, or they'd shadow the PKGBUILD's own arrays.

list_arrays() {
    (
        set +u +e
        # shellcheck disable=SC1090
        source "$PKGBUILD_PATH" >/dev/null 2>&1
        compgen -A arrayvar | grep -xE "$1"
    ) || true
}

# Empty array must print nothing — a bare printf's blank line would look like a single "" element.
read_array() {
    (
        set +u +e
        # shellcheck disable=SC1090
        source "$PKGBUILD_PATH" >/dev/null 2>&1
        eval "(( \${#$1[@]} )) && printf '%s\n' \"\${$1[@]}\""
    ) || true
}

# Always renders canonical multi-line, 2-space indent — both real PKGBUILDs already use it.
render_block() {
    local name="$1"
    shift
    local out="${name}=(" element
    for element in "$@"; do
        out+=$'\n'"  '${element}'"
    done
    out+=$'\n'")"
    printf '%s' "$out"
}

# A same-line close (sha256sums=('x') or sha256sums=()) must still end the block, or the rewrite eats the next one.
rewrite_block() {
    local name="$1" file="$2"

    NEW_BLOCK="$3" awk -v name="$name" '
        BEGIN { new = ENVIRON["NEW_BLOCK"] }
        !replaced && $0 ~ "^[[:space:]]*" name "=\\(" {
            print new
            replaced = 1
            if (index(substr($0, index($0, "(") + 1), ")") == 0) in_block = 1
            next
        }
        in_block { if (index($0, ")")) in_block = 0; next }
        { print }
    ' "$file" > "${file}.tmp"

    mv "${file}.tmp" "$file"
}

# Neither repo uses variant-suffixed arrays; refuse rather than silently skip an unverified source.
mapfile -t variants < <(list_arrays '^(sha256sums|source)_[A-Za-z0-9_]+$')
[ ${#variants[@]} -eq 0 ] || { echo "ERROR: variant-suffixed arrays not supported: ${variants[*]}"; exit 1; }

has_source="$(list_arrays '^source$')"
has_sums="$(list_arrays '^sha256sums$')"

if [ -z "$has_source" ] && [ -z "$has_sums" ]; then
    echo "No sha256sums=() array in $PKGBUILD_PATH — nothing to verify"
    exit 0
fi
[ -n "$has_source" ] || { echo "ERROR: sha256sums=() has no matching source=() array"; exit 1; }
[ -n "$has_sums" ] || { echo "ERROR: source=() has no matching sha256sums=() array — its sources would go unverified"; exit 1; }

mapfile -t sources < <(read_array source)
mapfile -t sums < <(read_array sha256sums)

if [ ${#sources[@]} -ne ${#sums[@]} ]; then
    echo "ERROR: source count (${#sources[@]}) != sha256sums count (${#sums[@]})"
    exit 1
fi

changed=false
new_sums=()

for i in ${sources[@]+"${!sources[@]}"}; do
    src="${sources[$i]}"
    sum="${sums[$i]}"

    local_name="$src"
    [[ "$src" == *"::"* ]] && local_name="${src%%::*}"

    if [[ "$src" == *"://"* ]]; then
        echo "  REMOTE: $src (keeping $sum)"
        new_sums+=("$sum")
        continue
    fi

    filepath="$PKGDIR/$local_name"

    # SKIP must survive either way — re-hashing a version-stamped source breaks the next release build.
    if [ -f "$filepath" ]; then
        if [ "$sum" = "SKIP" ]; then
            echo "  SKIP (preserved): $local_name"
            new_sums+=("SKIP")
        else
            actual=$(sha256sum "$filepath" | cut -d' ' -f1)
            if [ "$sum" != "$actual" ]; then
                echo "  MISMATCH -> $actual : $local_name (was $sum)"
                new_sums+=("$actual")
                changed=true
            else
                echo "  OK: $local_name"
                new_sums+=("$sum")
            fi
        fi
    else
        if [ "$sum" = "SKIP" ]; then
            echo "  SKIP (file absent, acceptable): $local_name"
        else
            echo "  WARNING: $local_name not found, keeping hash"
        fi
        new_sums+=("$sum")
    fi
done

if [ "$changed" = true ]; then
    if [ "$AUTOFIX" = "true" ]; then
        rewrite_block sha256sums "$PKGBUILD_PATH" \
            "$(render_block sha256sums ${new_sums[@]+"${new_sums[@]}"})"
        echo "PKGBUILD updated with correct checksums"
        exit 2
    fi
    echo "FAIL: checksums need updating (autofix disabled)"
    exit 1
fi

echo "All checksums verified"
exit 0
