#!/usr/bin/env bash
set -euo pipefail
repo="${1:?Usage: falcon install owner/repository}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Use owner/repository' >&2; exit 2; }
root="$(pwd)"; modules="$root/modules"; name="${repo##*/}"; dest="$modules/$name"
[[ ! -e "$dest" ]] || { echo "Module already exists: $dest" >&2; exit 1; }
mkdir -p "$modules"
git clone --depth 1 "https://github.com/$repo.git" "$dest"
python3 - "$dest" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1]).resolve()
data=json.loads((root/'module.json').read_text(encoding='utf-8-sig'))
main=data.get('main',''); entry=(root/main).resolve()
if not main.endswith('.fc') or root not in entry.parents or not entry.is_file():
    raise SystemExit('module.json main must be an existing .fc file within the repository')
PY
echo "Installed $repo into modules/$name"
