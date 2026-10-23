# Runs the project-owned Sculptory test server on 127.0.0.1:25580, under .local/run/server.
# Offline mode so dev clients can join, and this machine only, since "BuilderDev" is op whoever joins as that name;
# other names are not op.
# The dev-only playtest setup runs by default (PlaytestSetup): the time is locked at day, mob spawning is off, the
# difficulty is peaceful and hostile mobs are removed; "Sculptory playtest setup:" lines in the console show it.
#   scripts/playtest-server.ps1
#   scripts/playtest-server.ps1 -NoSetup      # without it (the world's own time, game rules, difficulty and mobs;
#                                             #   what an earlier run with it saved in the world stays)
param(
    [switch]$NoSetup
)
$ErrorActionPreference = 'Stop'
$Port = 25580
$root = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $root '.local\run\server'
New-Item -ItemType Directory -Force $runDir | Out-Null

$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listening) { throw "Port $Port is already in use (PID $($listening[0].OwningProcess)). Stop that server first." }

Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true' -Encoding ascii
$props = Join-Path $runDir 'server.properties'
$settings = [ordered]@{
    'server-port' = "$Port"; 'server-ip' = '127.0.0.1'; 'online-mode' = 'false'; 'gamemode' = 'creative'
    'level-name' = 'bs-test'
    'spawn-protection' = '16'; 'motd' = 'Sculptory test server'
}
$lines = if (Test-Path $props) { Get-Content $props } else { @() }
foreach ($key in $settings.Keys) {
    $lines = @($lines | Where-Object { $_ -notmatch "^$([regex]::Escape($key))=" }) + "$key=$($settings[$key])"
}
Set-Content -Path $props -Value $lines -Encoding ascii

# Offline-mode UUID for "BuilderDev": MD5("OfflinePlayer:" + name) as a version-3 UUID.
$md5 = [Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:BuilderDev'))
$md5[6] = ($md5[6] -band 0x0f) -bor 0x30; $md5[8] = ($md5[8] -band 0x3f) -bor 0x80
$hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
$uuid = '{0}-{1}-{2}-{3}-{4}' -f $hex.Substring(0,8), $hex.Substring(8,4), $hex.Substring(12,4), $hex.Substring(16,4), $hex.Substring(20,12)
$ops = "[{`"uuid`":`"$uuid`",`"name`":`"BuilderDev`",`"level`":4,`"bypassesPlayerLimit`":false}]"
Set-Content -Path (Join-Path $runDir 'ops.json') -Value $ops -Encoding ascii

$gradleArgs = @(':fabric:runServer', '--console=plain')
if (-not $NoSetup) { $gradleArgs += '-PbsPlaytest' }
Write-Host "Starting test server on localhost:$Port (Ctrl+C or 'stop' to quit)."
& (Join-Path $PSScriptRoot 'gradle.ps1') @gradleArgs
exit $LASTEXITCODE
