$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = Join-Path $root 'build/installer'
dotnet publish (Join-Path $PSScriptRoot 'FalconSetup.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o $output
if ($LASTEXITCODE -ne 0) { throw 'Could not publish FalconSetup.' }
$exe = Join-Path $output 'FalconSetup.exe'
if (!(Test-Path -LiteralPath $exe)) { throw 'FalconSetup.exe was not produced.' }
Write-Host "Built $exe"
