#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
# SPDX-License-Identifier: AGPL-3.0-only
#
# Stamps the release version into the API change registry: replaces `since = ApiVersion.CURRENT`,
# `since = CURRENT` and `since = "current"` with `since = "<version>"`. The registry file holds only
# @ApiChange annotations. Imports are never touched. Idempotent.
#
# Usage: stamp-api-changes.sh <X.Y.Z> <registry-file>...
# Exit codes: 0 ok, 1 a `since` that is not a quoted X.Y.Z literal is left, 2 usage error

set -Eeuo pipefail

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: $0 <X.Y.Z> <registry-file>..." >&2; exit 2; }
shift
[ $# -gt 0 ] || { echo "usage: $0 <X.Y.Z> <registry-file>..." >&2; exit 2; }
for f in "$@"; do [ -f "$f" ] || { echo "not a file: $f" >&2; exit 2; }; done

status=0
for f in "$@"; do
    sed -E -z -i \
        -e 's/([(,][[:space:]]*since[[:space:]]*=)[[:space:]]*\n[[:space:]]*/\1 /g' \
        -e "s/([(,][[:space:]]*since[[:space:]]*=[[:space:]]*)([A-Za-z_\$][A-Za-z0-9_\$]*\.)*CURRENT\b/\1\"$version\"/g" \
        -e "s/([(,][[:space:]]*since[[:space:]]*=[[:space:]]*)\"current\"/\1\"$version\"/g" "$f"
    bad=$(grep -nE '(^|[(,])[[:space:]]*since[[:space:]]*(=|$)' "$f" | grep -vE 'since[[:space:]]*=[[:space:]]*"[0-9]+\.[0-9]+\.[0-9]+"' || true)
    if [ -n "$bad" ]; then
        echo "$f: since is not a quoted X.Y.Z literal:" >&2
        echo "$bad" >&2
        status=1
    fi
done
exit "$status"
