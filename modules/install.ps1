param([Parameter(Mandatory=$true)][string]$Repository)
$ErrorActionPreference='Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Use falcon install owner/repository' }
$root=(Get-Location).Path
$modules=Join-Path $root 'modules'
$name=($Repository -split '/')[-1]
$destination=Join-Path $modules $name
if (Test-Path $destination) { throw "Module already exists: $destination" }
New-Item -ItemType Directory -Force $modules | Out-Null
try {
  git clone --depth 1 "https://github.com/$Repository.git" $destination
  if ($LASTEXITCODE -ne 0) { throw 'git clone failed' }
  $manifest=Join-Path $destination 'module.json'
  if (!(Test-Path $manifest)) { throw 'Repository needs a module.json manifest' }
  $data=Get-Content $manifest -Raw | ConvertFrom-Json
  $main=[IO.Path]::GetFullPath((Join-Path $destination $data.main))
  if (!$data.main.EndsWith('.fc') -or !$main.StartsWith([IO.Path]::GetFullPath($destination),[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path $main)) { throw 'module.json main must point to an existing .fc file within the repo' }
  Write-Host "Installed $Repository. Import it with: use `"$name`" as $name"
} catch { Remove-Item $destination -Recurse -Force -ErrorAction SilentlyContinue; throw }
