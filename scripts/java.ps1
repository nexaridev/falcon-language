function Find-FalconJava {
    param([switch]$Compiler)
    $tool = if ($Compiler) { 'javac.exe' } else { 'java.exe' }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME "bin/$tool" }
    $command = Get-Command $tool -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    $roots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        (Join-Path $env:LOCALAPPDATA 'Programs/Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java')
    )
    foreach ($root in $roots) {
        $candidates += @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin/$tool" })
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (!(Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        # Capture Java's stderr without shell quoting or NativeCommandError behavior.
        $start = New-Object System.Diagnostics.ProcessStartInfo
        $start.FileName = $candidate
        $start.Arguments = '-version'
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::Start($start)
        try {
            $version = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
            $process.WaitForExit()
            $succeeded = $process.ExitCode -eq 0
        } finally { $process.Dispose() }
        if ($succeeded -and $version -match '(?:version\s+"|javac\s+)(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -ge 17) { return $candidate }
        }
    }
    $requirement = if ($Compiler) { 'JDK' } else { 'Java runtime' }
    throw "Falcon requires a $requirement version 17 or newer. Set JAVA_HOME to its directory."
}
