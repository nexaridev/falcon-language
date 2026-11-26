#!/usr/bin/env bash
set -euo pipefail
repo="${1:?Usage: falcon install owner/repository [--path folder] [--ref branch-or-tag] [--name module]}"; shift
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Use owner/repository' >&2; exit 2; }
package_path=""; ref=""; name=""
while (($#)); do
  case "$1" in
    --path) (($# >= 2)) || { echo '--path needs a folder' >&2; exit 2; }; package_path="$2"; shift 2 ;;
    --ref) (($# >= 2)) || { echo '--ref needs a branch or tag' >&2; exit 2; }; ref="$2"; shift 2 ;;
    --name) (($# >= 2)) || { echo '--name needs a module name' >&2; exit 2; }; name="$2"; shift 2 ;;
    *) echo "Unknown module install option: $1" >&2; exit 2 ;;
  esac
done
root="$(pwd)"; modules="$root/modules"
name="${name:-${package_path##*/}}"; name="${name:-${repo##*/}}"
[[ "$name" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo 'Module name may contain only letters, numbers, dots, underscores, and hyphens.' >&2; exit 2; }
dest="$modules/$name"
[[ ! -e "$dest" ]] || { echo "Module already exists: $dest" >&2; exit 1; }
mkdir -p "$modules"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
clone_target="$dest"; [[ -z "$package_path" ]] || clone_target="$tmp/repo"
clone_args=(clone --depth 1); [[ -z "$ref" ]] || clone_args+=(--branch "$ref")
echo 'Falcon module install: validating the request...'
echo "Falcon module install: fetching $repo${ref:+ at $ref}..."
echo "Falcon module install: target is $dest"
git "${clone_args[@]}" "https://github.com/$repo.git" "$clone_target"
if [[ -n "$package_path" ]]; then
  echo "Falcon module install: selecting repository folder '$package_path'..."
  source_dir="$(realpath "$tmp/repo/$package_path")"
  case "$source_dir/" in "$tmp/repo/"*) ;; *) echo 'Invalid --path folder.' >&2; exit 2 ;; esac
  [[ -d "$source_dir" ]] || { echo 'The requested --path folder does not exist in the repository.' >&2; exit 1; }
  cp -a "$source_dir" "$dest"
fi
echo 'Falcon module install: checking module manifest or folder initializer...'
python3 - "$dest" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1]).resolve()
manifest=root/'module.json'
if (root/'__init__.fc').is_file():
    main='__init__.fc'
elif (root/'init.fc').is_file():
    main='init.fc'
elif manifest.is_file():
    data=json.loads(manifest.read_text(encoding='utf-8-sig'))
    main=data.get('main','')
else:
    main=''
entry=(root/main).resolve()
if not main.endswith('.fc') or root not in entry.parents or not entry.is_file():
    raise SystemExit('module must provide __init__.fc, init.fc, or a valid module.json main entry')
print(f"Falcon module install: verified '{main}'")
PY
if [[ ( -f "$dest/__init__.fc" || -f "$dest/init.fc" ) && -f "$root/$name.fc" ]]; then
  echo "Module name conflict: folder '$dest' contains an initializer and file '$root/$name.fc' already exists. Keep one module form." >&2
  rm -rf "$dest"
  exit 1
fi
echo "Falcon module install: installed to $dest"
echo "Import it with: use \"$name\" as $name"
