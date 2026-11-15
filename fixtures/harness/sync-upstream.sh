#!/usr/bin/env bash
# Copies the iTerm2 v3.7.7 sources listed in upstream-files.txt into
# Sources/<target>/Upstream/. Off Darwin it also rewrites `import CryptoKit`
# to `import Crypto` (swift-crypto) and applies linux.patch.
set -euo pipefail

UPSTREAM_URL="https://gitlab.com/gnachman/iterm2.git"
UPSTREAM_TAG="v3.7.7"
UPSTREAM_COMMIT="e0025340b49a2fc6415f490d77bbead5d3e0583e"

harness_dir="$(cd "$(dirname "$0")" && pwd)"
file_list="$harness_dir/upstream-files.txt"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
checkout="$work_dir/iterm2"

entries="$(grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$file_list")"
paths="$(awk '{ print $2 }' <<<"$entries")"

git init --quiet "$checkout"
git -C "$checkout" remote add origin "$UPSTREAM_URL"
git -C "$checkout" sparse-checkout set --no-cone $(sed 's|^|/|' <<<"$paths")
git -C "$checkout" fetch --quiet --depth 1 --filter=blob:none origin "refs/tags/$UPSTREAM_TAG"
fetched_commit="$(git -C "$checkout" rev-parse 'FETCH_HEAD^{commit}')"
if [[ "$fetched_commit" != "$UPSTREAM_COMMIT" ]]; then
    echo "error: $UPSTREAM_TAG is $fetched_commit, expected $UPSTREAM_COMMIT" >&2
    exit 1
fi
git -C "$checkout" checkout --quiet "$UPSTREAM_COMMIT"

for target in CompanionProtocol WireFixtures; do
    rm -rf "${harness_dir:?}/Sources/$target/Upstream"
done

while read -r target path; do
    case "$target" in
        CompanionProtocol|WireFixtures) ;;
        *) echo "error: unknown target '$target' for $path" >&2; exit 1 ;;
    esac
    destination="$harness_dir/Sources/$target/Upstream/$path"
    mkdir -p "$(dirname "$destination")"
    cp "$checkout/$path" "$destination"
done <<<"$entries"

if [[ "$(uname -s)" != "Darwin" ]]; then
    find "$harness_dir/Sources" -path '*/Upstream/*' -name '*.swift' \
        -exec sed -i 's/^import CryptoKit$/import Crypto/' {} +
    patch --forward --quiet -p1 -d "$harness_dir/Sources/WireFixtures/Upstream" < "$harness_dir/linux.patch"
fi

echo "Synced $(wc -l <<<"$entries" | tr -d ' ') files from $UPSTREAM_TAG ($UPSTREAM_COMMIT)"
