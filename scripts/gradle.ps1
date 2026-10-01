# Runs the Gradle wrapper with a JDK 21 without requiring a system-wide Java install.
# The JDK, first found wins:
#   1. $env:SCULPTORY_JDK (the old name $env:BUILDERSUITE_JDK still works);
#   2. a portable JDK in a folder named toolchains\jdk-21* beside the repository or beside one of its parent folders
#      (e.g. <parent>\toolchains\jdk-21.0.11+10 next to <parent>\sculptory); the newest name wins;
#   3. $env:JAVA_HOME.
$root = Split-Path -Parent $PSScriptRoot

function Test-Jdk([string] $dir) {
    return $dir -and (Test-Path (Join-Path $dir 'bin\java.exe'))
}

function Find-ToolchainJdk([string] $start) {
    $dir = Split-Path -Parent $start
    while ($dir) {
        $toolchains = Join-Path $dir 'toolchains'
        if (Test-Path $toolchains) {
            $found = Get-ChildItem -Path $toolchains -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending | Where-Object { Test-Jdk $_.FullName } | Select-Object -First 1
            if ($found) { return $found.FullName }
        }
        $parent = Split-Path -Parent $dir
        if ($parent -eq $dir) { break }
        $dir = $parent
    }
    return $null
}

$jdk = if ($env:SCULPTORY_JDK) { $env:SCULPTORY_JDK } elseif ($env:BUILDERSUITE_JDK) { $env:BUILDERSUITE_JDK } else { $null }
if (-not $jdk) { $jdk = Find-ToolchainJdk $root }
if (-not $jdk -and (Test-Jdk $env:JAVA_HOME)) { $jdk = $env:JAVA_HOME }
if (-not (Test-Jdk $jdk)) {
    Write-Host "No JDK 21 found$(if ($jdk) { " at '$jdk'" }). Set `$env:SCULPTORY_JDK (or JAVA_HOME) to a JDK 21 directory."
    exit 1
}
$env:JAVA_HOME = $jdk
# Windows PowerShell 5.1 turns redirected native stderr (e.g. javac notes) into error records;
# keep them non-terminating and rely on Gradle's exit code.
$ErrorActionPreference = 'Continue'
& (Join-Path $root 'gradlew.bat') -p $root @args
exit $LASTEXITCODE
