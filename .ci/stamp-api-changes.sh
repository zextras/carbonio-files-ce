#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
# SPDX-License-Identifier: AGPL-3.0-only
#
# Release stamper: replaces the floating `since` of the @ApiChange lines of a registry with the
# release version. Used by semantic-release (prepareCmd), before the release commit.
#
# Usage: stamp-api-changes.sh <version> <registry-file>...
#
# Only the `since` attribute of @ApiChange(...) annotations (single or multi line) is rewritten,
# when its value is ApiVersion.CURRENT, CURRENT or "current": it becomes "<version>". Comments,
# strings and other annotations are left alone. The import of ApiVersion (and the static import of
# CURRENT) is removed when nothing else uses it. Running it twice changes nothing.
#
# After stamping, every registry is checked again: the script exits 1, printing the offending
# lines, when a `current` (CURRENT or "current") remains inside an @ApiChange, or when a `since`
# is not a string literal. <version> must be X.Y.Z.
#
# Exit codes: 0 ok, 1 something is still floating after stamping, 2 usage or unexpected error.
# Needs bash, awk (POSIX), GNU head and sed. No Java.

set -Eeuo pipefail

TAG="[stamp-api-changes]"
rc=0
trap 'rc=$?; echo "$TAG ERROR: unexpected failure (exit $rc) at line $LINENO" >&2; exit 2' ERR

die() {
    echo "$TAG ERROR: $*" >&2
    exit 2
}

case "${1:-}" in
    -h | --help) sed -n '2,/^set -Eeuo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

[ $# -ge 2 ] || die "usage: stamp-api-changes.sh <version> <registry-file>..."
VERSION="$1"
shift
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    die "version must be X.Y.Z (got '$VERSION')"
fi
for f in "$@"; do
    [ -f "$f" ] || die "registry file not found: $f"
done

AWK_PROG=$(
    cat << 'AWK'
function skipstr(p,   q, c) {
    if (substr(s, p, 3) == "\"\"\"") {
        p += 3
        while (p <= n) {
            c = substr(s, p, 1)
            if (c == "\\") { p += 2; continue }
            if (substr(s, p, 3) == "\"\"\"") return p + 3
            p++
        }
        return n + 1
    }
    q = substr(s, p, 1); p++
    while (p <= n) {
        c = substr(s, p, 1)
        if (c == "\\") { p += 2; continue }
        if (c == q) return p + 1
        if (c == "\n") return p
        p++
    }
    return n + 1
}
function skipws(p,   c, d, e) {
    while (p <= n) {
        c = substr(s, p, 1)
        if (c == " " || c == "\t" || c == "\r" || c == "\n") { p++; continue }
        d = substr(s, p, 2)
        if (d == "//") { e = index(substr(s, p), "\n"); p = e ? p + e : n + 1; continue }
        if (d == "/*") { e = index(substr(s, p + 2), "*/"); p = e ? p + 2 + e + 1 : n + 1; continue }
        break
    }
    return p
}
function idend(p) {
    while (p <= n && substr(s, p, 1) ~ /[A-Za-z0-9_$]/) p++
    return p
}
function qualend(p,   q) {
    q = idend(p)
    while (substr(s, q, 1) == "." && substr(s, q + 1, 1) ~ /[A-Za-z_$]/) q = idend(q + 1)
    return q
}
function flag(pos, msg,   pre, ln) {
    pre = substr(s, 1, pos - 1)
    ln = gsub(/\n/, "\n", pre) + 1
    if (seen[ln]) return
    seen[ln] = 1
    bad++
    printf "%s:%d: %s: %s\n", file, ln, msg, lines[ln] > "/dev/stderr"
}
function record(r, t) {
    if (mode == "stamp") { ne++; es[ne] = r; ee[ne] = t }
    else flag(r, "since is still current")
}
function useall(v,   parts, k, i) {
    k = split(v, parts, ".")
    for (i = 1; i <= k; i++) used[parts[i]] = 1
}
function args(q,   depth, c, d, e, t, r, id, v, k) {
    depth = 1
    q++
    while (q <= n && depth > 0) {
        c = substr(s, q, 1); d = substr(s, q, 2)
        if (d == "//" || d == "/*") { q = skipws(q); continue }
        if (c == "\"" || c == "'") {
            t = skipstr(q)
            if (mode == "check" && tolower(substr(s, q, t - q)) == "\"current\"") flag(q, "\"current\" inside @ApiChange")
            q = t; continue
        }
        if (c == "(") { depth++; q++; continue }
        if (c == ")") { depth--; q++; continue }
        if (c ~ /[A-Za-z_$]/) {
            e = idend(q); id = substr(s, q, e - q)
            if (id == "since" && depth == 1) {
                r = skipws(e)
                if (substr(s, r, 1) == "=" && substr(s, r + 1, 1) != "=") {
                    r = skipws(r + 1)
                    if (substr(s, r, 1) == "\"") {
                        t = skipstr(r)
                        if (tolower(substr(s, r, t - r)) == "\"current\"") record(r, t)
                        q = t; continue
                    }
                    if (substr(s, r, 1) ~ /[A-Za-z_$]/) {
                        t = qualend(r); v = substr(s, r, t - r)
                        k = v; sub(/^.*\./, "", k)
                        if (k == "CURRENT") record(r, t)
                        else {
                            useall(v)
                            if (mode == "check") flag(r, "since is not a string literal")
                        }
                        q = t; continue
                    }
                    if (mode == "check") flag(r, "since is not a string literal")
                    q = r; continue
                }
            }
            if (id == "CURRENT" && mode == "check") flag(q, "CURRENT inside @ApiChange")
            used[id] = 1
            q = e; continue
        }
        q++
    }
    return q
}
function annotation(p,   q, e, name, k) {
    q = skipws(p + 1)
    if (substr(s, q, 1) !~ /[A-Za-z_$]/) return p + 1
    e = qualend(q); name = substr(s, q, e - q)
    k = name; sub(/^.*\./, "", k)
    if (k != "ApiChange") return p + 1
    q = skipws(e)
    if (substr(s, q, 1) != "(") return q
    return args(q)
}
function scan(   p, c, d, e, id) {
    p = 1
    while (p <= n) {
        c = substr(s, p, 1); d = substr(s, p, 2)
        if (d == "//" || d == "/*") { p = skipws(p); continue }
        if (c == "\"" || c == "'") { p = skipstr(p); continue }
        if (c == "@") { p = annotation(p); continue }
        if (c ~ /[A-Za-z_$]/) {
            e = idend(p); id = substr(s, p, e - p)
            if (id == "import") {
                while (e <= n && substr(s, e, 1) != ";") e++
                p = e + 1; continue
            }
            used[id] = 1; p = e; continue
        }
        if (c ~ /[0-9]/) { p = idend(p); continue }
        p++
    }
}
{ s = s $0 "\n" }
END {
    n = length(s)
    split(s, lines, "\n")
    scan()
    if (mode == "check") exit (bad > 0 ? 1 : 0)
    out = ""; last = 1
    for (i = 1; i <= ne; i++) {
        out = out substr(s, last, es[i] - last) "\"" version "\""
        last = ee[i]
    }
    out = out substr(s, last)
    cnt = split(out, L, "\n")
    res = ""
    for (i = 1; i <= cnt; i++) {
        drop = 0
        if (L[i] ~ /^[ \t]*import[ \t]+static[ \t]+[A-Za-z0-9_.$]*ApiVersion[.]CURRENT[ \t]*;/ && !used["CURRENT"]) drop = 1
        if (L[i] ~ /^[ \t]*import[ \t]+static[ \t]+[A-Za-z0-9_.$]*ApiVersion[.]\*[ \t]*;/ && !used["CURRENT"] && !used["ApiVersion"]) drop = 1
        if (L[i] ~ /^[ \t]*import[ \t]+([A-Za-z0-9_$]+[.])*ApiVersion[ \t]*;/ && !used["ApiVersion"]) drop = 1
        if (drop) continue
        res = res L[i] (i < cnt ? "\n" : "")
    }
    printf "%s", res
}
AWK
)

tmp=$(mktemp)
trap 'rm -f "${tmp:?}"' EXIT
failed=0

for f in "$@"; do
    awk -v mode=stamp -v version="$VERSION" -v file="$f" "$AWK_PROG" "$f" > "$tmp"
    if [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ]; then
        head -c -1 "$tmp" > "$tmp.nl"
        cat "$tmp.nl" > "$tmp"
        rm -f "${tmp:?}.nl"
    fi
    if cmp -s "$tmp" "$f"; then
        echo "$TAG $f: unchanged"
    else
        cat "$tmp" > "$f"
        echo "$TAG $f: stamped with $VERSION"
    fi
    if ! awk -v mode=check -v file="$f" "$AWK_PROG" "$f"; then
        failed=1
    fi
done

if [ "$failed" -ne 0 ]; then
    echo "$TAG FATAL: the lines above still hold a floating or non-literal since inside @ApiChange" >&2
    exit 1
fi
