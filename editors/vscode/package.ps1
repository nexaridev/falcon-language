param([switch]$Install)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$null = & (Join-Path $root 'build.ps1') -Test
$runtimeDirectory = Join-Path $PSScriptRoot 'runtime'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'build/falcon.jar') -Destination (Join-Path $runtimeDirectory 'falcon.jar') -Force
$standardDirectory = Join-Path $PSScriptRoot 'stdlib'
New-Item -ItemType Directory -Path $standardDirectory -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $root 'stdlib') -Filter '*.fc' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $standardDirectory -Force
}
$version = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'package.json') -Raw | ConvertFrom-Json).version
$vsix = Join-Path $root "build/falcon-language-$version.vsix"
Push-Location $PSScriptRoot
try {
    & npx.cmd --yes '@vscode/vsce' package --allow-missing-repository --skip-license --out $vsix
    if ($LASTEXITCODE -ne 0) { throw 'VS Code extension packaging failed.' }
} finally { Pop-Location }
if ($Install) {
    & code.cmd --install-extension $vsix --force
    if ($LASTEXITCODE -ne 0) { throw 'VS Code extension installation failed.' }
}
Write-Host "Packaged $vsix"
