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
sqlite_version=3.54.1.0
sqlite_jar="$root/build/deps/sqlite-jdbc-$sqlite_version.jar"
mkdir -p "$root/build/deps"
if [ ! -f "$sqlite_jar" ]; then
  curl -fL "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/$sqlite_version/sqlite-jdbc-$sqlite_version.jar" -o "$sqlite_jar"
fi
(cd "$classes" && jar --extract --file "$sqlite_jar")
cp "$root"/stdlib/*.fc "$classes/stdlib/"
printf 'Main-Class: falcon.Main\nMulti-Release: true\nEnable-Native-Access: ALL-UNNAMED\n\n' > "$classes/falcon-manifest.mf"
jar --create --file "$root/build/falcon.jar" --manifest "$classes/falcon-manifest.mf" -C "$classes" .
echo "Built $root/build/falcon.jar"
javac --release 17 -encoding UTF-8 -cp "$root/build/falcon.jar" -d "$root/build/test-classes" "$root"/src/test/java/falcon/*.java
java -cp "$root/build/falcon.jar:$root/build/test-classes" falcon.FalconTest
java -cp "$root/build/falcon.jar:$root/build/test-classes" falcon.BuildOptionsTest
