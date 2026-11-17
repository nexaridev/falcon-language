$ErrorActionPreference = 'Stop'
$previousInputEncoding = [Console]::InputEncoding
$previousOutputEncoding = [Console]::OutputEncoding
$previousPipeEncoding = $OutputEncoding
$exitCode = 2
try {
    # Match the runtime's UTF-8 reader and keep Windows PowerShell from using
    # a legacy console code page when it decodes Java's output.
    $utf8 = [Text.UTF8Encoding]::new($false)
    [Console]::InputEncoding = $utf8
    [Console]::OutputEncoding = $utf8
    $OutputEncoding = $utf8
    $jar = Join-Path $PSScriptRoot 'build/falcon.jar'
    if (!(Test-Path -LiteralPath $jar)) { $null = & (Join-Path $PSScriptRoot 'build.ps1') }
    . (Join-Path $PSScriptRoot 'scripts/java.ps1')
    $runtime = Find-FalconJava
    & $runtime '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Dfalcon.stdlib=$(Join-Path $PSScriptRoot 'stdlib')" -jar $jar @args
    $exitCode = $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
} finally {
    [Console]::InputEncoding = $previousInputEncoding
    [Console]::OutputEncoding = $previousOutputEncoding
    $OutputEncoding = $previousPipeEncoding
}
exit $exitCode
