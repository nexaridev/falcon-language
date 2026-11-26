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
  $profileBlock = "# >>> Falcon CLI alias >>>`r`nSet-Alias -Name fc -Value falcon -Scope Global -Force`r`n# <<< Falcon CLI alias <<<"
  $documents = [Environment]::GetFolderPath([Environment+SpecialFolder]::MyDocuments)
  foreach ($shell in @('PowerShell','WindowsPowerShell')) {
    $profileFile = Join-Path $documents "$shell\Microsoft.PowerShell_profile.ps1"
    New-Item -ItemType Directory -Path (Split-Path $profileFile) -Force | Out-Null
    $profileText = if (Test-Path -LiteralPath $profileFile) { Get-Content -LiteralPath $profileFile -Raw } else { '' }
    $start = $profileText.IndexOf('# >>> Falcon CLI alias >>>',[StringComparison]::Ordinal)
    if ($start -ge 0) {
      $finish = $profileText.IndexOf('# <<< Falcon CLI alias <<<',$start,[StringComparison]::Ordinal)
      if ($finish -ge 0) { $profileText = $profileText.Remove($start,$finish + '# <<< Falcon CLI alias <<<'.Length - $start).TrimEnd() }
    }
    $updatedProfile = if ($profileText.Length -eq 0) { $profileBlock } else { $profileText + "`r`n`r`n" + $profileBlock }
    [IO.File]::WriteAllText($profileFile,$updatedProfile + "`r`n",[Text.UTF8Encoding]::new($false))
  }
  Set-Alias -Name fc -Value falcon -Scope Global -Force
  $commandKey = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\Microsoft\Command Processor')
  try {
    $autoRun = [string]$commandKey.GetValue('AutoRun','',[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    if ($autoRun -notmatch '(?i)doskey\s+fc=') {
      $macro = 'doskey fc=call "' + (Join-Path $bin 'fc.cmd') + '" $*'
      $updatedAutoRun = if ([string]::IsNullOrWhiteSpace($autoRun)) { $macro } else { $autoRun.TrimEnd() + ' & ' + $macro }
      $kind = try { $commandKey.GetValueKind('AutoRun') } catch { [Microsoft.Win32.RegistryValueKind]::String }
      $commandKey.SetValue('AutoRun',$updatedAutoRun,$kind)
    }
  } finally { $commandKey.Dispose() }
  Write-Host 'Falcon installed and added to your user PATH.'
  Write-Host 'Try: falcon --version or fc --version'
  $answer = Read-Host 'Proceed with installing the VS Code extension using editors/vscode/package.ps1? [Y/n]'
  if ($answer -notmatch '^(?i:n|no)$') {
    $packageScript = Join-Path $root 'editors/vscode/package.ps1'
    if (-not (Test-Path -LiteralPath $packageScript -PathType Leaf)) {
      Write-Warning "Falcon CLI is installed, but the VS Code packaging script was not found: $packageScript"
    } else {
      try {
        Write-Host 'Installing the Falcon VS Code extension...'
        & $packageScript -Install
        if ($LASTEXITCODE -ne 0) { throw "VS Code installer exited with code $LASTEXITCODE." }
      } catch {
        Write-Warning "Falcon CLI is installed, but the VS Code extension could not be installed: $($_.Exception.Message)"
        Write-Host "You can retry with: $packageScript -Install"
      }
    }
  } else {
    Write-Host 'Skipping the VS Code extension. Run editors/vscode/package.ps1 -Install later to install it.'
  }
} finally {
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
