# Launches the Sculptory dev client for a playtest.
#   scripts/playtest.ps1                      # singleplayer; opens world "BS Dev" if it exists
#   scripts/playtest.ps1 -World "My World"    # open a different singleplayer world
#   scripts/playtest.ps1 -Server              # join the project test server (scripts/playtest-server.ps1)
#   scripts/playtest.ps1 -Server -User Guest  # second, non-op player for permission checks
#   scripts/playtest.ps1 -Sodium              # with Sodium (render-mod compatibility check)
#   scripts/playtest.ps1 -Iris                # with Iris and the Sodium it needs; pick a shader pack in
#                                             #   Options > Video Settings > Shader Packs (packs go in
#                                             #   .local/run/client/shaderpacks)
#   scripts/playtest.ps1 -Size 1600x900       # game window size
#   scripts/playtest.ps1 -Tour                # dev-only screenshot tour of the editor UI:
#                                             #   opens world "BS Tour", takes the pictures, quits; the pictures and
#                                             #   index.txt go to <main checkout>\.local\tour\<yyyyMMdd-HHmmss>
#   scripts/playtest.ps1 -Tour -TourDir <dir> # ...into another directory (its old pictures are replaced)
#                                             # The tour shows the developer's own view: the game maximised (-Size WxH
#                                             #   instead), the main checkout's editor UI size (-UiSize <percent>
#                                             #   instead) and window layout (-DefaultLayout for the defaults).
#   scripts/playtest.ps1 -Tour -Wiki          # the wiki pictures instead: cropped,
#                                             #   written straight into this checkout's docs/wiki/images as
#                                             #   <page-id>-<what>.png; index.txt goes to <main>\.local\tour\wiki-<time>
#                                             #   (or -TourDir). Then run scripts/check-wiki.ps1.
#   scripts/playtest.ps1 -Check               # dev-only play check: scripted scenarios in
#                                             #   the real client on a fresh copy of the tour world ("BS Check <time>"),
#                                             #   PASS/FAIL per check and pictures in report.txt, then quits. Output:
#                                             #   <main checkout>\.local\check\<yyyyMMdd-HHmmss> (or -CheckDir <dir>).
#   scripts/playtest.ps1 -Check -Sodium       # the visual subset with Sodium (-Iris: with Iris and the pinned dev
#                                             #   shader pack; -CheckSuite full for every scenario)
#   scripts/playtest.ps1 -Check -CheckOnly water,select  # only the scenarios whose names start so
#   scripts/playtest.ps1 -Check -Server       # two-client check, player A (op) on the test server; with
#                                             #   -User Guest -RunDir client-b: player B (not op), in its own game
#                                             #   directory. Give both the same -CheckDir; start B once A has
#                                             #   joined (two builds compiling at once break a starting client).
#   scripts/playtest.ps1 -Demo                # the scripted demonstration for a screen recording: a fresh copy of the tour world ("BS Demo <time>"), the client
#                                             #   at its default window size (maximise it, or -DemoMaximize), then
#                                             #   "Press Enter when recording"; Enter plays the demo with captions and
#                                             #   leaves the client open. report.txt (one line per step) goes to
#                                             #   <main checkout>\.local\demo\<yyyyMMdd-HHmmss> (or -DemoDir <dir>) and
#                                             #   is printed once the client is closed.
#   scripts/playtest.ps1 -Demo -DemoFrom 8    # a retake from step 8 (a number or a step id such as "paste")
#   scripts/playtest.ps1 -Demo -DemoAutoStart 10  # starts 10 s after "Press Enter" shows, Enter or not (unattended)
#   scripts/playtest.ps1 -Demo -NoCaptions    # no caption bar, title or closing card after Enter (a voice-over take);
#                                             #   each result is held about a second longer to talk over
# Sodium and Iris are dev-client-only downloads (versions pinned in gradle.properties), never bundled.
param(
    [string]$World = 'BS Dev',
    [switch]$Server,
    [string]$User = 'BuilderDev',
    [switch]$Sodium,
    [switch]$Iris,
    [string]$Size = '',
    [switch]$Tour,
    [string]$TourDir = '',
    [int]$UiSize = 0,
    [switch]$DefaultLayout,
    [switch]$Wiki,
    [switch]$Check,
    [string]$CheckDir = '',
    [string]$CheckSuite = '',
    [string]$CheckOnly = '',
    [switch]$Demo,
    [string]$DemoFrom = '',
    [string]$DemoDir = '',
    [switch]$DemoMaximize,
    [int]$DemoAutoStart = 0,
    [switch]$NoCaptions,
    [string]$RunDir = ''
)
$root = Split-Path -Parent $PSScriptRoot

# Copies a world another process may have open, reading only: every file is opened for reading with full sharing
# (the process that has it open keeps writing, renaming and deleting), session.lock is left out, and the copy goes to a new directory
# that is renamed into place when complete.
function Copy-WorldReadOnly([string]$From, [string]$To) {
    $source = (Resolve-Path -LiteralPath $From).Path.TrimEnd('\')
    $partial = "$To.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
    Get-ChildItem -LiteralPath $source -Recurse -File | Where-Object { $_.Name -ne 'session.lock' } | ForEach-Object {
        $target = Join-Path $partial $_.FullName.Substring($source.Length).TrimStart('\')
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        $in = [System.IO.File]::Open($_.FullName, 'Open', 'Read', 'ReadWrite, Delete')
        try {
            $out = [System.IO.File]::Create($target)
            try { $in.CopyTo($out) } finally { $out.Dispose() }
        } finally {
            $in.Dispose()
        }
    }
    Rename-Item -LiteralPath $partial -NewName (Split-Path -Leaf $To)
}

# One frozen copy of the playtest server's world in the main checkout, shared by every worktree, so before and after
# pictures show the same terrain. Made on first use (read only from the server's world); $null when there is none.
function Get-WorldTemplate([string]$Main) {
    $template = Join-Path $Main '.local\tour\world-template'
    if (-not (Test-Path -LiteralPath $template)) {
        $serverWorld = Join-Path $Main '.local\run\server\bs-test'
        if (-not (Test-Path -LiteralPath $serverWorld)) {
            Write-Host "No world template: the playtest server world '$serverWorld' doesn't exist."
            return $null
        }
        Write-Host "Copying the playtest server world (read only) to $template"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $template) | Out-Null
        Copy-WorldReadOnly $serverWorld $template
    }
    return $template
}

# The play check's pinned dev-only shader pack (gradle.properties check_shaderpack_*): downloaded once into the main
# checkout's .local\shaderpacks (its SHA-1 checked), copied into the game directory's shaderpacks, and switched on in
# Iris's config. Returns the pack's file name, or $null when it can't be had (the check then runs Iris without one).
function Enable-CheckShaderPack([string]$Main, [string]$GameDir) {
    $props = @{}
    Get-Content (Join-Path $root 'gradle.properties') | Where-Object { $_ -match '^[a-z0-9_]+=' } | ForEach-Object {
        $key, $value = $_ -split '=', 2
        $props[$key] = $value.Trim()
    }
    $versionId = $props['check_shaderpack_version']
    $sha1 = $props['check_shaderpack_sha1']
    if (-not $versionId -or -not $sha1) { return $null }
    $store = Join-Path $Main '.local\shaderpacks'
    New-Item -ItemType Directory -Force -Path $store | Out-Null
    $pack = Get-ChildItem -LiteralPath $store -File -ErrorAction SilentlyContinue |
        Where-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA1).Hash -eq $sha1.ToUpperInvariant() } |
        Select-Object -First 1
    if (-not $pack) {
        try {
            $version = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/version/$versionId" -TimeoutSec 60
            $file = $version.files | Where-Object { $_.hashes.sha1 -eq $sha1 } | Select-Object -First 1
            if (-not $file) { Write-Host "Shader pack version $versionId has no file with SHA-1 $sha1"; return $null }
            $target = Join-Path $store $file.filename
            Invoke-WebRequest -Uri $file.url -OutFile "$target.partial" -TimeoutSec 120
            if ((Get-FileHash -LiteralPath "$target.partial" -Algorithm SHA1).Hash -ne $sha1.ToUpperInvariant()) {
                Remove-Item -LiteralPath "$target.partial"
                Write-Host 'The downloaded shader pack does not match its pinned SHA-1'
                return $null
            }
            Move-Item -LiteralPath "$target.partial" -Destination $target -Force
            $pack = Get-Item -LiteralPath $target
        } catch {
            Write-Host "Could not download the shader pack: $($_.Exception.Message)"
            return $null
        }
    }
    $packs = Join-Path $GameDir 'shaderpacks'
    New-Item -ItemType Directory -Force -Path $packs | Out-Null
    Copy-Item -LiteralPath $pack.FullName -Destination (Join-Path $packs $pack.Name) -Force
    $irisFile = Join-Path $GameDir 'config\iris.properties'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $irisFile) | Out-Null
    $lines = if (Test-Path -LiteralPath $irisFile) { Get-Content -LiteralPath $irisFile } else { @() }
    $lines = @($lines | Where-Object { $_ -notmatch '^(shaderPack|enableShaders)=' }) + "shaderPack=$($pack.Name)" + 'enableShaders=true'
    Set-Content -LiteralPath $irisFile -Value $lines -Encoding ascii
    return $pack.Name
}

# Whether no running game holds a world: its session.lock (if any) opens without sharing.
function Test-WorldFree([string]$WorldDir) {
    $lock = Join-Path $WorldDir 'session.lock'
    if (-not (Test-Path -LiteralPath $lock)) { return $true }
    try {
        [System.IO.File]::Open($lock, 'Open', 'ReadWrite', 'None').Dispose()
        return $true
    } catch {
        return $false
    }
}

$gradleArgs = @(':fabric:runClient', "-PbsUser=$User", '--console=plain')
# A second client's own game directory under .local\run (two clients must not share options, config and logs).
$clientDir = Join-Path $root '.local\run\client'
if ($RunDir) {
    if ($RunDir -notmatch '^[A-Za-z0-9_-]+$') {
        Write-Host '-RunDir is a directory name under .local\run, e.g. client-b'
        exit 1
    }
    $clientDir = Join-Path $root ".local\run\$RunDir"
    $gradleArgs += "-PbsRunDir=../../.local/run/$RunDir"
}
if ($Iris) {
    $gradleArgs += '-PbsIris'
} elseif ($Sodium) {
    $gradleArgs += '-PbsSodium'
}
if ($Wiki -and -not $Tour) {
    Write-Host '-Wiki goes with -Tour: scripts/playtest.ps1 -Tour -Wiki'
    exit 1
}
if (($Check -and $Tour) -or ($Demo -and ($Tour -or $Check -or $Server))) {
    Write-Host '-Check, -Tour and -Demo are separate singleplayer runs'
    exit 1
}
if ($Tour) {
    # The main checkout (screenshots outlive worktrees): the parent of the shared .git directory.
    $commonDir = (& git -C $root rev-parse --path-format=absolute --git-common-dir).Trim()
    $main = Split-Path -Parent $commonDir
    if (-not $TourDir) {
        $prefix = if ($Wiki) { 'wiki-' } else { '' }
        $TourDir = Join-Path $main (".local\tour\" + $prefix + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    $TourDir = [System.IO.Path]::GetFullPath($TourDir)
    $tourWorld = Join-Path $clientDir 'saves\BS Tour'
    if (-not (Test-Path -LiteralPath $tourWorld)) {
        $template = Get-WorldTemplate $main
        if (-not $template) { exit 1 }
        Write-Host "Creating world 'BS Tour' from $template"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $tourWorld) | Out-Null
        Copy-WorldReadOnly $template $tourWorld
    }
    # A new run directory (a fresh worktree) gets the main checkout's game options: no tutorial, no onboarding.
    $options = Join-Path $clientDir 'options.txt'
    $mainOptions = Join-Path $main '.local\run\client\options.txt'
    if (-not (Test-Path -LiteralPath $options) -and (Test-Path -LiteralPath $mainOptions)) {
        Copy-Item -LiteralPath $mainOptions -Destination $options
    }
    # The developer's own view: the editor UI size and window layout from the main checkout, the window maximised.
    $mainConfig = Join-Path $main '.local\run\client\config\sculptory'
    # Before the renamed mod first runs there, the main checkout still has the old Builder Suite folder.
    $oldMainConfig = Join-Path $main '.local\run\client\config\buildersuite'
    if (-not (Test-Path -LiteralPath $mainConfig) -and (Test-Path -LiteralPath $oldMainConfig)) { $mainConfig = $oldMainConfig }
    $uiFile = Join-Path $mainConfig 'editor-ui.json'
    if ($UiSize -le 0 -and (Test-Path -LiteralPath $uiFile)) {
        $UiSize = [int](Get-Content -Raw -LiteralPath $uiFile | ConvertFrom-Json).uiSize
    }
    if ($UiSize -gt 0) { $gradleArgs += "-PbsTourUiSize=$UiSize" }
    $layoutFile = Join-Path $mainConfig 'editor-layout.json'
    if (-not $DefaultLayout -and (Test-Path -LiteralPath $layoutFile)) { $gradleArgs += "-PbsTourLayout=$layoutFile" }
    if (-not $Size) { $gradleArgs += '-PbsTourMaximize' }
    $gradleArgs += '-PbsWorld=BS Tour'
    $gradleArgs += "-PbsTour=$TourDir"
    if ($Wiki) {
        $wikiImages = Join-Path $root 'docs\wiki\images'
        $gradleArgs += "-PbsTourWiki=$wikiImages"
        Write-Host "Wiki pictures into $wikiImages, the index into $TourDir (the client quits by itself when done)"
    } else {
        Write-Host "Screenshot tour into $TourDir (the client quits by itself when done)"
    }
} elseif ($Check) {
    $commonDir = (& git -C $root rev-parse --path-format=absolute --git-common-dir).Trim()
    $main = Split-Path -Parent $commonDir
    $role = if (-not $Server) { 'solo' } elseif ($User -eq 'BuilderDev') { 'a' } else { 'b' }
    $renderer = if ($Iris) { 'Iris' } elseif ($Sodium) { 'Sodium' } else { 'vanilla' }
    if (-not $CheckDir) {
        $suffix = if ($role -eq 'solo') { '' } else { '-2p' }
        $CheckDir = Join-Path $main (".local\check\" + (Get-Date -Format 'yyyyMMdd-HHmmss') + $suffix)
    }
    $CheckDir = [System.IO.Path]::GetFullPath($CheckDir)
    if ($role -ne 'solo') { $CheckDir = Join-Path $CheckDir $role }
    if (-not $CheckSuite) { $CheckSuite = if ($Iris -or $Sodium) { 'visual' } else { 'full' } }
    if ($role -eq 'solo') {
        # A fresh copy of the frozen world each run, under a new name (never into an existing directory); earlier
        # check worlds no game holds are removed.
        $template = Get-WorldTemplate $main
        if (-not $template) { exit 1 }
        $saves = Join-Path $clientDir 'saves'
        New-Item -ItemType Directory -Force -Path $saves | Out-Null
        Get-ChildItem -LiteralPath $saves -Directory -Filter 'BS Check *' | ForEach-Object {
            if (Test-WorldFree $_.FullName) { Remove-Item -LiteralPath $_.FullName -Recurse -Force }
        }
        $checkWorld = 'BS Check ' + (Get-Date -Format 'yyyyMMdd-HHmmss')
        Write-Host "Creating world '$checkWorld' from $template"
        Copy-WorldReadOnly $template (Join-Path $saves $checkWorld)
        $gradleArgs += "-PbsWorld=$checkWorld"
    } else {
        $gradleArgs += '-PbsServer=localhost:25580'
    }
    # A new game directory gets the main checkout's game options: no tutorial, no onboarding.
    $options = Join-Path $clientDir 'options.txt'
    $mainOptions = Join-Path $main '.local\run\client\options.txt'
    New-Item -ItemType Directory -Force -Path $clientDir | Out-Null
    if (-not (Test-Path -LiteralPath $options) -and (Test-Path -LiteralPath $mainOptions)) {
        Copy-Item -LiteralPath $mainOptions -Destination $options
    }
    if ($Iris) {
        # With a shader pack ghosts are outlines (a known limit): the check shows what the player gets.
        $packName = Enable-CheckShaderPack $main $clientDir
        $renderer = if ($packName) { "Iris + $packName" } else { 'Iris (no shader pack)' }
    }
    if (-not $Size) { $Size = '1600x900' }
    $gradleArgs += "-PbsCheck=$CheckDir"
    $gradleArgs += "-PbsCheckRole=$role"
    $gradleArgs += "-PbsCheckSuite=$CheckSuite"
    $gradleArgs += "-PbsCheckLabel=$renderer"
    if ($CheckOnly) { $gradleArgs += "-PbsCheckOnly=$CheckOnly" }
    Write-Host "Play check ($role, $renderer, $CheckSuite) into $CheckDir (the client quits by itself when done)"
} elseif ($Demo) {
    $commonDir = (& git -C $root rev-parse --path-format=absolute --git-common-dir).Trim()
    $main = Split-Path -Parent $commonDir
    if (-not $DemoDir) {
        $DemoDir = Join-Path $main (".local\demo\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    $DemoDir = [System.IO.Path]::GetFullPath($DemoDir)
    # A fresh copy of the frozen world each run, under a new name; earlier demo worlds no game holds are removed.
    $template = Get-WorldTemplate $main
    if (-not $template) { exit 1 }
    $saves = Join-Path $clientDir 'saves'
    New-Item -ItemType Directory -Force -Path $saves | Out-Null
    Get-ChildItem -LiteralPath $saves -Directory -Filter 'BS Demo *' | ForEach-Object {
        if (Test-WorldFree $_.FullName) { Remove-Item -LiteralPath $_.FullName -Recurse -Force }
    }
    $demoWorld = 'BS Demo ' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    Write-Host "Creating world '$demoWorld' from $template"
    Copy-WorldReadOnly $template (Join-Path $saves $demoWorld)
    $gradleArgs += "-PbsWorld=$demoWorld"
    # A new game directory gets the main checkout's game options: no tutorial, no onboarding.
    $options = Join-Path $clientDir 'options.txt'
    $mainOptions = Join-Path $main '.local\run\client\options.txt'
    if (-not (Test-Path -LiteralPath $options) -and (Test-Path -LiteralPath $mainOptions)) {
        Copy-Item -LiteralPath $mainOptions -Destination $options
    }
    $gradleArgs += "-PbsDemo=$DemoDir"
    if ($DemoFrom) { $gradleArgs += "-PbsDemoFrom=$DemoFrom" }
    if ($DemoMaximize) { $gradleArgs += '-PbsDemoMaximize' }
    if ($DemoAutoStart -gt 0) { $gradleArgs += "-PbsDemoAutoStart=$DemoAutoStart" }
    if ($NoCaptions) { $gradleArgs += '-PbsDemoNoCaptions' }
    Write-Host "Demo into $DemoDir. Start recording, then press Enter in the game; close the game when it is over."
} elseif ($Server) {
    $gradleArgs += '-PbsServer=localhost:25580'
} elseif (Test-Path (Join-Path $root ".local\run\client\saves\$World")) {
    $gradleArgs += "-PbsWorld=$World"
} else {
    Write-Host "World '$World' not found yet. Minecraft will open at the title screen:"
    Write-Host "  Singleplayer > Create New World > name it '$World', Game Mode: Creative, Allow Cheats: ON."
    Write-Host "  Next time this script opens it directly."
}
if ($Size) {
    $gradleArgs += "-PbsSize=$Size"
}
& (Join-Path $PSScriptRoot 'gradle.ps1') @gradleArgs
$code = $LASTEXITCODE
if ($Tour) {
    $index = Join-Path $TourDir 'index.txt'
    if (Test-Path -LiteralPath $index) {
        $lines = Get-Content -LiteralPath $index -Encoding UTF8
        $lines | Where-Object { $_.StartsWith('#') } | ForEach-Object { Write-Host $_ }
        $lines | Where-Object { $_ -match '\| (FAILED|SKIPPED):' } | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host 'The tour wrote no index.txt (see .local\run\client\logs\latest.log).'
    }
    Write-Host "Tour output: $TourDir"
    if ($Wiki) { Write-Host "Wiki pictures: $wikiImages (check them with scripts\check-wiki.ps1)" }
}
if ($Check) {
    $report = Join-Path $CheckDir 'report.txt'
    if (Test-Path -LiteralPath $report) {
        # The report is UTF-8 without a BOM (a toast's text may hold an em dash); Windows PowerShell would read it as ANSI.
        $lines = Get-Content -LiteralPath $report -Encoding UTF8
        $lines | Where-Object { $_.StartsWith('#') } | Select-Object -First 3 | ForEach-Object { Write-Host $_ }
        $lines | Where-Object { $_.StartsWith('FAIL') } | Select-Object -Unique | ForEach-Object { Write-Host "  $_" }
    } else {
        Write-Host "The check wrote no report.txt (see $clientDir\logs\latest.log)."
    }
    Write-Host "Play check output: $CheckDir"
}
if ($Demo) {
    $report = Join-Path $DemoDir 'report.txt'
    if (Test-Path -LiteralPath $report) {
        Get-Content -LiteralPath $report -Encoding UTF8 | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "The demo wrote no report.txt (see $clientDir\logs\latest.log)."
    }
    Write-Host "Demo output: $DemoDir"
}
exit $code
