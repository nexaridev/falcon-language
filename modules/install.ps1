param(
  [Parameter(Mandatory=$true,Position=0)][string]$Repository,
  [string]$Path = '',
  [string]$Ref = '',
  [string]$Name = ''
)
$ErrorActionPreference='Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Use falcon install owner/repository [--path folder] [--ref branch-or-tag] [--name module]' }
$root=(Get-Location).Path
$modules=Join-Path $root 'modules'
$name=if ($Name) { $Name } elseif ($Path) { [IO.Path]::GetFileName($Path.TrimEnd('/','\')) } else { ($Repository -split '/')[-1] }
if ($name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Module name may contain only letters, numbers, dots, underscores, and hyphens.' }
$destination=Join-Path $modules $name
if (Test-Path $destination) { throw "Module already exists: $destination" }
New-Item -ItemType Directory -Force $modules | Out-Null
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('falcon-module-'+[guid]::NewGuid().ToString('N'))
$cloneTarget=if ($Path) { $scratch } else { $destination }
try {
  $cloneArgs=@('clone','--depth','1')
  if ($Ref) { $cloneArgs += @('--branch',$Ref) }
  $cloneArgs += @("https://github.com/$Repository.git",$cloneTarget)
  & git @cloneArgs
  if ($LASTEXITCODE -ne 0) { throw 'Git could not clone that repository/ref.' }
  if ($Path) {
    $repoRoot=[IO.Path]::GetFullPath($scratch).TrimEnd('\')+'\'
    $source=[IO.Path]::GetFullPath((Join-Path $scratch $Path))
    if (!$source.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $source -PathType Container)) { throw 'The requested --path folder does not exist in the repository.' }
    Copy-Item -LiteralPath $source -Destination $destination -Recurse
  }
  $manifest=Join-Path $destination 'module.json'
  if (!(Test-Path $manifest)) { throw 'Module needs a module.json manifest' }
  $data=Get-Content $manifest -Raw | ConvertFrom-Json
  $moduleRoot=[IO.Path]::GetFullPath($destination).TrimEnd('\')+'\'
  $main=[IO.Path]::GetFullPath((Join-Path $destination $data.main))
  if (!$data.main.EndsWith('.fc') -or !$main.StartsWith($moduleRoot,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $main -PathType Leaf)) { throw 'module.json main must point to an existing .fc file within the module' }
  Write-Host "Installed $Repository$(if ($Path) { ":$Path" }) as $name. Import it with: use `"$name`" as $name"
} catch { if (Test-Path $destination) { Remove-Item $destination -Recurse -Force -ErrorAction SilentlyContinue }; throw }
finally { if (Test-Path $scratch) { Remove-Item $scratch -Recurse -Force -ErrorAction SilentlyContinue } }
