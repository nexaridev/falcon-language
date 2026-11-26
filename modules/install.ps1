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
Write-Host 'Falcon module install: validating the request...'
Write-Host "Falcon module install: fetching $Repository$(if ($Ref) { " at $Ref" })..."
Write-Host "Falcon module install: target is $destination"
try {
  $cloneArgs=@('clone','--depth','1')
  if ($Ref) { $cloneArgs += @('--branch',$Ref) }
  $cloneArgs += @("https://github.com/$Repository.git",$cloneTarget)
  & git @cloneArgs
  if ($LASTEXITCODE -ne 0) { throw 'Git could not clone that repository/ref.' }
  if ($Path) {
    Write-Host "Falcon module install: selecting repository folder '$Path'..."
    $repoRoot=[IO.Path]::GetFullPath($scratch).TrimEnd('\')+'\'
    $source=[IO.Path]::GetFullPath((Join-Path $scratch $Path))
    if (!$source.StartsWith($repoRoot,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $source -PathType Container)) { throw 'The requested --path folder does not exist in the repository.' }
    Copy-Item -LiteralPath $source -Destination $destination -Recurse
  }
  Write-Host 'Falcon module install: checking module manifest or folder initializer...'
  $manifest=Join-Path $destination 'module.json'
  if (Test-Path (Join-Path $destination '__init__.fc')) { $entryFile='__init__.fc' }
  elseif (Test-Path (Join-Path $destination 'init.fc')) { $entryFile='init.fc' }
  elseif (Test-Path $manifest) {
    $data=Get-Content $manifest -Raw | ConvertFrom-Json
    $entryFile=$data.main
  } else { throw 'Module needs __init__.fc, init.fc, or module.json with a .fc main path' }
  $moduleRoot=[IO.Path]::GetFullPath($destination).TrimEnd('\')+'\'
  $main=[IO.Path]::GetFullPath((Join-Path $destination $entryFile))
  if (!$entryFile.EndsWith('.fc') -or [IO.Path]::IsPathRooted($entryFile) -or !$main.StartsWith($moduleRoot,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $main -PathType Leaf)) { throw 'Module entry must be an existing .fc file inside the module folder' }
  $conflictingFile=Join-Path $root ($name+'.fc')
  if ($entryFile -in @('__init__.fc','init.fc') -and (Test-Path -LiteralPath $conflictingFile -PathType Leaf)) {
    throw "Module name conflict: folder '$destination' contains '$entryFile' and file '$conflictingFile' already exists. Keep one module form."
  }
  Write-Host "Falcon module install: verified '$entryFile'"
  Write-Host "Falcon module install: installed to $destination"
  Write-Host "Import it with: use `"$name`" as $name"
} catch { if (Test-Path $destination) { Remove-Item $destination -Recurse -Force -ErrorAction SilentlyContinue }; throw }
finally { if (Test-Path $scratch) { Remove-Item $scratch -Recurse -Force -ErrorAction SilentlyContinue } }
