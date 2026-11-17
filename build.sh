#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
command -v javac >/dev/null || { echo 'JDK 17+ required (javac not found).' >&2; exit 1; }
command -v jar >/dev/null || { echo 'JDK 17+ required (jar not found).' >&2; exit 1; }
classes="$root/build/classes"
rm -rf "$classes"
mkdir -p "$classes/stdlib"
mapfile -t sources < <(find "$root/src/main/java/falcon" -name '*.java' -print)
javac --release 17 -encoding UTF-8 -d "$classes" "${sources[@]}"
cp "$root"/stdlib/*.fc "$classes/stdlib/"
jar --create --file "$root/build/falcon.jar" --main-class falcon.Main -C "$classes" .
echo "Built $root/build/falcon.jar"
