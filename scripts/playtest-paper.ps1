# Runs the project's Paper 1.21.1 test server, a real Paper install under .local/installs, with the Sculptory plugin
# built in this checkout, on 127.0.0.1:25580 (offline mode, so dev clients can join; this machine only).
#   scripts/playtest-paper.ps1                  # copy the newest plugin jar in, set up, start; this window is the log
#   scripts/playtest-paper.ps1 -Install <dir>   # another Paper install (default .local/installs/paper-1.21.1-133)
#   scripts/playtest-paper.ps1 -Jar <file>      # this plugin jar instead of the newest one in modules/paper/build/libs
#   scripts/playtest-paper.ps1 -NoSetup         # without the playtest conditions below (port, address, offline mode
#                                               #   and the op are still set)
#   scripts/playtest-paper.ps1 -Stop            # stop the server this script started (sends "stop" to its console)
# The plugin has no dev-only playtest setup (the loaders' dev runs have PlaytestSetup), so the script sets the same
# conditions from outside: "BuilderDev" is op at level 4 (ops.json, offline-mode UUID), server.properties says creative,
# peaceful and no monsters, and once the server is up its console gets the game rules: the daylight cycle off, the time
# set to day, mob spawning off and the difficulty peaceful, in all three worlds. "Playtest setup:" lines show them.
# The console: every line appended to .local/run/paper-stdin.txt goes to the server (any tool can append to it, e.g.
# Add-Content .local/run/paper-stdin.txt 'say hi'); lines already in the file when the server starts are skipped.
param(
    [string]$Install,
    [string]$Jar,
    [string]$Memory = '3G',
    [switch]$NoSetup,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$Port = 25580
$root = Split-Path -Parent $PSScriptRoot
# The server installs and the console file are the main checkout's (.local is per checkout, and a git worktree has
# none of its own installs); the plugin jar is this checkout's build.
$mainRoot = $root
try {
    $commonDir = git -C $root rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($LASTEXITCODE -eq 0 -and $commonDir) { $mainRoot = Split-Path -Parent ([IO.Path]::GetFullPath($commonDir)) }
} catch {
    # No git: this checkout is the main one.
}
$runDir = Join-Path $mainRoot '.local\run'
$stdinFile = Join-Path $runDir 'paper-stdin.txt'
$pidFile = Join-Path $runDir 'paper-server.pid'
New-Item -ItemType Directory -Force $runDir | Out-Null
if (-not (Test-Path $stdinFile)) { New-Item -ItemType File $stdinFile | Out-Null }

function Get-OurServer {
    # The server this script started: its PID file names a java process that is still running.
    if (-not (Test-Path $pidFile)) { return $null }
    $savedPid = [int](Get-Content $pidFile -TotalCount 1)
    $process = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -eq 'java') { return $process }
    return $null
}

if ($Stop) {
    $server = Get-OurServer
    if (-not $server) { Write-Host 'No Paper server started by this script is running.'; exit 1 }
    # Appended with shared access, retried while the running script has the file open for a moment.
    $sent = $false
    for ($try = 0; $try -lt 50 -and -not $sent; $try++) {
        try {
            $stream = [IO.File]::Open($stdinFile, 'Append', 'Write', 'ReadWrite')
            $bytes = [Text.Encoding]::ASCII.GetBytes("stop`n")
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Close()
            $sent = $true
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    if (-not $sent) { throw "Could not append to $stdinFile" }
    Write-Host "Sent 'stop' to the Paper server (PID $($server.Id)); it saves the worlds and exits."
    exit 0
}

if (-not $Install) { $Install = Join-Path $mainRoot '.local\installs\paper-1.21.1-133' }
$Install = (Resolve-Path $Install).Path
$paperJar = Get-ChildItem -Path $Install -Filter 'paper-*.jar' -File | Select-Object -First 1
if (-not $paperJar) { throw "No paper-*.jar in $Install" }

# Never replace a plugin jar a running server has open: nothing may listen on the port, and no java process may run
# this install's Paper jar.
$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listening) { throw "Port $Port is already in use (PID $($listening[0].OwningProcess)). Stop that server first." }
$running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($paperJar.Name) }
if ($running) { throw "A java process runs $($paperJar.Name) (PID $($running[0].ProcessId)). Stop it first." }

# The plugin: the newest jar the build made (not the -dev jar with the self-test), or the one asked for.
if ($Jar) {
    $plugin = Get-Item (Resolve-Path $Jar).Path
} else {
    $plugin = Get-ChildItem -Path (Join-Path $root 'modules\paper\build\libs') -Filter 'sculptory-paper-*.jar' -File `
            -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*-dev.jar' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $plugin) { throw 'No plugin jar in modules/paper/build/libs: run scripts/gradle.ps1 :paper:jar first.' }
}
$plugins = Join-Path $Install 'plugins'
New-Item -ItemType Directory -Force $plugins | Out-Null
# One Sculptory jar at a time: Paper would refuse a second plugin of the same name.
Get-ChildItem -Path $plugins -Filter 'sculptory-paper-*.jar' -File | Remove-Item
Copy-Item -Path $plugin.FullName -Destination $plugins
Write-Host "Plugin: $($plugin.Name)"

# server.properties: the keys below are set, every other key stays as it is (read and written byte for byte).
$latin1 = [Text.Encoding]::GetEncoding('ISO-8859-1')
Set-Content -Path (Join-Path $Install 'eula.txt') -Value 'eula=true' -Encoding ascii
$props = Join-Path $Install 'server.properties'
$settings = [ordered]@{ 'server-port' = "$Port"; 'server-ip' = '127.0.0.1'; 'online-mode' = 'false' }
if (-not $NoSetup) {
    $settings['gamemode'] = 'creative'; $settings['difficulty'] = 'peaceful'; $settings['spawn-monsters'] = 'false'
}
$lines = if (Test-Path $props) { [IO.File]::ReadAllLines($props, $latin1) } else { @() }
foreach ($key in $settings.Keys) {
    $lines = @($lines | Where-Object { $_ -notmatch "^$([regex]::Escape($key))=" }) + "$key=$($settings[$key])"
}
[IO.File]::WriteAllLines($props, [string[]]$lines, $latin1)

# ops.json: BuilderDev at level 4, other entries kept. Offline-mode UUID: MD5("OfflinePlayer:" + name) as a
# version-3 UUID, as scripts/playtest-server.ps1 computes it.
$md5 = [Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes('OfflinePlayer:BuilderDev'))
$md5[6] = ($md5[6] -band 0x0f) -bor 0x30; $md5[8] = ($md5[8] -band 0x3f) -bor 0x80
$hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
$uuid = '{0}-{1}-{2}-{3}-{4}' -f $hex.Substring(0,8), $hex.Substring(8,4), $hex.Substring(12,4), $hex.Substring(16,4), $hex.Substring(20,12)
$opsFile = Join-Path $Install 'ops.json'
$ops = @()
if (Test-Path $opsFile) {
    $ops = @((Get-Content $opsFile -Raw | ConvertFrom-Json) | Where-Object { $_ -and $_.name -ne 'BuilderDev' -and $_.uuid -ne $uuid })
}
$ops += [pscustomobject]@{ uuid = $uuid; name = 'BuilderDev'; level = 4; bypassesPlayerLimit = $false }
Set-Content -Path $opsFile -Value (ConvertTo-Json -InputObject @($ops) -Depth 3) -Encoding ascii

# The console commands of the playtest setup, sent once the server is up. Paper keeps game rules, time and difficulty
# per world, and its console acts on the overworld; the other two get theirs through execute.
$setupCommands = @(
    'gamerule doDaylightCycle false',
    'time set day',
    'gamerule doMobSpawning false',
    'difficulty peaceful'
)
foreach ($dimension in 'minecraft:the_nether', 'minecraft:the_end') {
    $setupCommands += "execute in $dimension run gamerule doDaylightCycle false"
    $setupCommands += "execute in $dimension run gamerule doMobSpawning false"
    $setupCommands += "execute in $dimension run difficulty peaceful"
}

. (Join-Path $PSScriptRoot 'jdk.ps1')
$jdk = Resolve-SculptoryJdk $root
if (-not (Test-Jdk $jdk)) { throw "No JDK 21 found$(if ($jdk) { " at '$jdk'" }). Set `$env:SCULPTORY_JDK to a JDK 21 directory." }

# The server's output is read on thread-pool threads into a queue (a compiled handler: PowerShell script blocks can't
# run there), and this loop prints it, watches for the start-up line and forwards the console file.
if (-not ('SculptoryPaperConsole' -as [type])) {
    Add-Type -TypeDefinition @'
using System.Collections.Concurrent;
using System.Diagnostics;
public static class SculptoryPaperConsole {
    public static void Attach(Process process, ConcurrentQueue<string> lines) {
        process.OutputDataReceived += (sender, e) => { if (e.Data != null) lines.Enqueue(e.Data); };
        process.ErrorDataReceived += (sender, e) => { if (e.Data != null) lines.Enqueue(e.Data); };
    }
}
'@
}
$info = New-Object Diagnostics.ProcessStartInfo
$info.FileName = Join-Path $jdk 'bin\java.exe'
$info.Arguments = "-Xms1G -Xmx$Memory -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar `"$($paperJar.Name)`" --nogui"
$info.WorkingDirectory = $Install
$info.UseShellExecute = $false
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$info.StandardOutputEncoding = [Text.Encoding]::UTF8
$info.StandardErrorEncoding = [Text.Encoding]::UTF8
$server = New-Object Diagnostics.Process
$server.StartInfo = $info
$output = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
[SculptoryPaperConsole]::Attach($server, $output)

# Lines already in the console file are old; only what is appended from now on goes to the server.
$position = (Get-Item $stdinFile).Length
$pending = ''
Write-Host "Starting Paper ($($paperJar.Name)) on 127.0.0.1:$Port from $Install. Stop it with -Stop or 'stop' in the console file."
# The pipe to the server's console takes the console's input encoding; a UTF-8 one with a byte order mark would put
# the mark in front of the first command ("?stop" is then an unknown command), so the server starts with plain UTF-8.
$consoleEncoding = [Console]::InputEncoding
[Console]::InputEncoding = New-Object Text.UTF8Encoding $false
try {
    [void]$server.Start()
} finally {
    [Console]::InputEncoding = $consoleEncoding
}
Set-Content -Path $pidFile -Value $server.Id -Encoding ascii
$server.BeginOutputReadLine()
$server.BeginErrorReadLine()
$server.StandardInput.AutoFlush = $true
$server.StandardInput.NewLine = "`n"
$setupDone = $NoSetup.IsPresent

function Send-Console([string] $command) {
    if (-not $server.HasExited) { $server.StandardInput.WriteLine($command) }
}

try {
    while ($true) {
        $line = $null
        while ($output.TryDequeue([ref]$line)) {
            Write-Host $line
            if (-not $setupDone -and $line -match 'Done \([0-9.,]+s\)!') {
                foreach ($command in $setupCommands) {
                    Write-Host "Playtest setup: $command"
                    Send-Console $command
                }
                $setupDone = $true
            }
        }
        if ($server.HasExited) { break }
        # Forward what was appended to the console file (a partial last line waits for its line break). The file is
        # opened only when it has grown, since a writer such as Add-Content fails while another handle has it open.
        $length = (Get-Item $stdinFile).Length
        $stream = $null
        if ($length -ne $position) {
            try { $stream = [IO.File]::Open($stdinFile, 'Open', 'Read', 'ReadWrite') } catch { $stream = $null }
        }
        if ($stream) {
            try {
                if ($stream.Length -lt $position) { $position = 0 }
                if ($stream.Length -gt $position) {
                    [void]$stream.Seek($position, 'Begin')
                    $bytes = New-Object byte[] ($stream.Length - $position)
                    $read = $stream.Read($bytes, 0, $bytes.Length)
                    $position += $read
                    $pending += [Text.Encoding]::UTF8.GetString($bytes, 0, $read)
                }
            } finally {
                $stream.Close()
            }
        }
        while ($pending.Contains("`n")) {
            $cut = $pending.IndexOf("`n")
            $command = $pending.Substring(0, $cut).Trim()
            $pending = $pending.Substring($cut + 1)
            if ($command) {
                Write-Host "Console: $command"
                Send-Console $command
            }
        }
        Start-Sleep -Milliseconds 200
    }
    $server.WaitForExit()
    $line = $null
    while ($output.TryDequeue([ref]$line)) { Write-Host $line }
    Write-Host "Paper exited with code $($server.ExitCode)."
    exit $server.ExitCode
} finally {
    Remove-Item -Path $pidFile -ErrorAction SilentlyContinue
}
