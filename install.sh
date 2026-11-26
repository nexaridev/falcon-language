#!/usr/bin/env bash
set -euo pipefail
repo="${1:-nexaridev/falcon-language}"
branch="${2:-main}"
command -v java >/dev/null || { echo 'Java 17+ is required.' >&2; exit 1; }
command -v git >/dev/null || { echo 'Git is required.' >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --depth 1 --branch "$branch" "https://github.com/$repo.git" "$tmp/falcon"
cp -R "$tmp/falcon"/. .
bash ./build.sh
chmod +x ./bin/falcon ./bin/fc
echo 'Falcon installed. Run ./bin/falcon --help'
