param([switch]$Test)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/java.ps1')
$compiler = Find-FalconJava -Compiler
$jdk = Split-Path (Split-Path $compiler)
$classes = Join-Path $PSScriptRoot 'build/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/falcon') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $jdk 'bin/javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Falcon compilation failed.' }
$standardClasses = Join-Path $classes 'stdlib'
New-Item -ItemType Directory -Path $standardClasses -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stdlib') -Filter '*.fc' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $standardClasses -Force
}
& (Join-Path $jdk 'bin/jar.exe') --create --file (Join-Path $PSScriptRoot 'build/falcon.jar') --main-class falcon.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Falcon JAR creation failed.' }
if ($Test) {
    $testClasses = Join-Path $PSScriptRoot 'build/test-classes'
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    $tests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java/falcon') -Filter '*.java' | ForEach-Object FullName)
    & (Join-Path $jdk 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $classes -d $testClasses @tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    & (Join-Path $jdk 'bin/java.exe') -cp "$classes;$testClasses" falcon.FalconTest
    if ($LASTEXITCODE -ne 0) { throw 'Falcon tests failed.' }
}
Write-Host 'Built build/falcon.jar'
# Return the resolved runtime path for the launcher; no global PATH changes.
Write-Output (Join-Path $jdk 'bin/java.exe')
