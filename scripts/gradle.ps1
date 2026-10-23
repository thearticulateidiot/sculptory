# Runs the Gradle wrapper with a JDK 21 without requiring a system-wide Java install.
# The JDK, first found wins (jdk.ps1):
#   1. $env:SCULPTORY_JDK (the old name $env:BUILDERSUITE_JDK still works);
#   2. a portable JDK in a folder named toolchains\jdk-21* beside the repository or beside one of its parent folders
#      (e.g. <parent>\toolchains\jdk-21.0.11+10 next to <parent>\sculptory); the newest name wins;
#   3. $env:JAVA_HOME.
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'jdk.ps1')

$jdk = Resolve-SculptoryJdk $root
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
