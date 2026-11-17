param([string]$Repository='nexaridev/falcon-language',[string]$Version='main')
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$zip=Join-Path $env:TEMP ([guid]::NewGuid().ToString()+'.zip')
$stage=Join-Path $env:TEMP ([guid]::NewGuid().ToString())
try {
  Invoke-WebRequest -Uri "https://github.com/$Repository/archive/refs/heads/$Version.zip" -OutFile $zip
  Expand-Archive -LiteralPath $zip -DestinationPath $stage
  $source=Get-ChildItem $stage -Directory | Select-Object -First 1
  Copy-Item -Path (Join-Path $source.FullName '*') -Destination $root -Recurse -Force
  & (Join-Path $root 'build.ps1')
  if ($LASTEXITCODE -ne 0) { throw 'Falcon build failed.' }
  $bin=[IO.Path]::GetFullPath((Join-Path $root 'bin')).TrimEnd('\')
  $userPath=[Environment]::GetEnvironmentVariable('Path','User')
  $entries=@($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  $alreadyAdded=$false
  foreach ($entry in $entries) {
    try {
      if ([IO.Path]::GetFullPath($entry.Trim().Trim('"')).TrimEnd('\') -ieq $bin) { $alreadyAdded=$true; break }
    } catch { }
  }
  if (-not $alreadyAdded) {
    $entries += $bin
    [Environment]::SetEnvironmentVariable('Path',($entries -join ';'),'User')
  }
  if (($env:Path -split ';' | Where-Object {
    try { [IO.Path]::GetFullPath($_.Trim().Trim('"')).TrimEnd('\') -ieq $bin } catch { $false }
  }).Count -eq 0) { $env:Path += ';' + $bin }
  Write-Host 'Falcon installed and added to your user PATH.'
  Write-Host 'Try: falcon --version'
} finally {
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
