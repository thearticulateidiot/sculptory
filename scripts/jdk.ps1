# Finds a JDK 21 without requiring a system-wide Java install; dot-sourced by gradle.ps1 and playtest-paper.ps1.
# Resolve-SculptoryJdk returns the JDK folder, first found wins, or $null:
#   1. $env:SCULPTORY_JDK (the old name $env:BUILDERSUITE_JDK still works);
#   2. a portable JDK in a folder named toolchains\jdk-21* beside the repository or beside one of its parent folders
#      (e.g. <parent>\toolchains\jdk-21.0.11+10 next to <parent>\sculptory); the newest name wins;
#   3. $env:JAVA_HOME.
# A folder named by 1 is returned even when it holds no java.exe, so the caller can say which setting is wrong.

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

function Resolve-SculptoryJdk([string] $repositoryRoot) {
    $jdk = if ($env:SCULPTORY_JDK) { $env:SCULPTORY_JDK } elseif ($env:BUILDERSUITE_JDK) { $env:BUILDERSUITE_JDK } else { $null }
    if (-not $jdk) { $jdk = Find-ToolchainJdk $repositoryRoot }
    if (-not $jdk -and (Test-Jdk $env:JAVA_HOME)) { $jdk = $env:JAVA_HOME }
    return $jdk
}
