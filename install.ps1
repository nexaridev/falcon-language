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
  Write-Host 'Falcon installed. Try: falcon run examples/hello.fc'
} finally {
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
