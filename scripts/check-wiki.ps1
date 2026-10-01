# Checks the wiki in docs/wiki and exits 1 on any error. The gate in
# `scripts/gradle.ps1 check` is WikiRepositoryTest (the in-game reader's parser); this script is the quick check while
# writing, without Gradle, and adds the wiki's own conventions: a home link under the title, a closing "Related"
# list, list items on one line,
# pictures no page shows, and the ~5 MB picture budget. It checks:
#   - every frozen page id has a page, each page starts with a "# " title, a home link line ("[Home](home.md) · ...")
#     right under it, and ends with a "## Related" list of two to four page links;
#   - every link is [text](page.md), [text](page.md#anchor), [text](#anchor) or [text](https://...), and the page and
#     the anchor exist (a heading's anchor: lower-cased, each run of characters other than a-z and 0-9 made one "-",
#     trimmed of "-"); no heading anchor twice on a page;
#   - every picture stands alone on its line as ![alt](images/<file>.png), exists, is a PNG at most 1200 px wide and
#     300 KB; the pictures together stay near 5 MB; a picture no page shows is reported;
#   - nothing outside the frozen Markdown subset: no HTML, code fences, indented code, deeper headings, "*" or "+"
#     bullets, deeper nesting, list items wrapped onto a second line, alignment markers or ragged tables, nested
#     blockquotes, footnotes, reference links, underscore emphasis, trailing spaces.
#   scripts/check-wiki.ps1              # the wiki in this checkout
#   scripts/check-wiki.ps1 -Wiki <dir>  # another wiki folder
param(
    [string]$Wiki = ''
)
$root = Split-Path -Parent $PSScriptRoot
if (-not $Wiki) { $Wiki = Join-Path $root 'docs\wiki' }
$Wiki = (Resolve-Path -LiteralPath $Wiki).Path

$frozen = @('home', 'getting-started', 'editor-mode', 'screen-and-windows', 'finding-things', 'tutorial',
    'tool-settings', 'presets', 'select', 'selection-operations', 'terrain-brushes', 'masks', 'shape-brush', 'generate',
    'extrude', 'fluid', 'scatter', 'clipboard', 'place', 'library', 'palettes', 'symmetry', 'history', 'keys',
    'server-setup', 'permissions', 'troubleshooting', 'faq', 'tinker', 'builder-mode', 'weather', 'navigation')
$maxPictureBytes = 300KB
$maxPictureWidth = 1200
$picturesBudget = 5MB

$script:errors = New-Object System.Collections.Generic.List[string]
$script:warnings = New-Object System.Collections.Generic.List[string]
function Fail([string]$where, [string]$what) { $script:errors.Add("$where : $what") }
function Warn([string]$where, [string]$what) { $script:warnings.Add("$where : $what") }

function Get-Anchor([string]$heading) {
    $anchor = [regex]::Replace($heading.ToLowerInvariant(), '[^a-z0-9]+', '-')
    return $anchor.Trim('-')
}

# Text with code spans blanked, so their contents (keys, paths, "<player>") are never taken for markup.
function Remove-CodeSpans([string]$line) {
    return [regex]::Replace($line, '`[^`]*`', { param($m) '`' + ('x' * ($m.Value.Length - 2)) + '`' })
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
# (PSBase.Keys: a page named "keys" would otherwise hide the table's Keys property.)
$pages = @{}
foreach ($file in Get-ChildItem -LiteralPath $Wiki -Filter '*.md' -File) {
    $id = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
    if ($id -notmatch '^[a-z0-9]+(-[a-z0-9]+)*$') {
        Fail $file.Name 'a page id is lower-case words joined by "-"'
    }
    $pages[$id] = [System.IO.File]::ReadAllLines($file.FullName, $utf8)
}
foreach ($id in $frozen) {
    if (-not $pages.ContainsKey($id)) { Fail "$id.md" 'frozen page is missing' }
}

# Headings and their anchors, per page.
$anchors = @{}
foreach ($id in $pages.PSBase.Keys) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    $lines = $pages[$id]
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^(#{1,3}) (.+)$') {
            $anchor = Get-Anchor $Matches[2]
            if (-not $set.Add($anchor)) { Fail "$id.md:$($i + 1)" "heading anchor '#$anchor' is used twice on the page" }
        }
    }
    $anchors[$id] = $set
}

$shown = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($id in ($pages.PSBase.Keys | Sort-Object)) {
    $lines = $pages[$id]
    $name = "$id.md"
    if ($lines.Count -eq 0 -or $lines[0] -notmatch '^# \S') { Fail "${name}:1" 'a page starts with its "# " title' }

    $inList = $false
    $inTable = $false
    $tableCells = 0
    $previous = ''
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $raw = $lines[$i]
        $at = "${name}:$($i + 1)"
        $line = Remove-CodeSpans $raw

        if ($raw -match '\s+$') { Fail $at 'trailing spaces' }
        if ($raw -match "`t") { Fail $at 'tab character' }
        if (($line -split '`').Count % 2 -eq 0) { Fail $at 'unclosed code span' }
        if ($raw -match '^\s*(```|~~~)') { Fail $at 'code fences are not in the subset' }
        if ($raw -match '^#{4,}') { Fail $at 'only #, ## and ### headings' }
        if ($raw -match '^#{1,3}[^# ]') { Fail $at 'a heading needs a space after its #' }
        if ($raw -match '^\s*[*+] ') { Fail $at 'bullets are "- " only' }
        if ($raw -match '^\s{4,}(- |\d+\. )') { Fail $at 'one level of list nesting only (two-space indent)' }
        if ($raw -match '^( |\s{3})(- |\d+\. )') { Fail $at 'a nested list item is indented by exactly two spaces' }
        if ($raw -match '^>\s*>') { Fail $at 'no nested blockquotes' }
        if ($line -match '<[A-Za-z/!]') { Fail $at 'no HTML' }
        if ($line -match '\[\^') { Fail $at 'no footnotes' }
        if ($line -match '\]\[' -or $line -match '^\s*\[[^\]]+\]:\s') { Fail $at 'no reference-style links' }
        if ($line -match '(^|[\s(])__?[A-Za-z]' -or $line -match '[A-Za-z]__?([\s).,;:]|$)') {
            Fail $at 'underscore emphasis is not in the subset (use *italic* or **bold**)'
        }
        if ($line -match '^#{1,3} .*\]\(') { Fail $at 'no links in headings' }

        # Lists: items stay on one line; nothing but a list item or a blank line continues a list.
        $isItem = $raw -match '^(  )?(- |\d+\. )'
        if ($isItem) {
            $inList = $true
        } elseif ($raw -eq '') {
            $inList = $false
        } elseif ($inList) {
            Fail $at 'list item wrapped onto a second line (write each item on one line)'
        } elseif ($raw -match '^\s+\S' -and -not $inTable) {
            Fail $at 'indented line outside a list (indented code is not in the subset)'
        }

        # Tables: header, a separator of dashes only, then rows with the same number of cells.
        if ($raw -match '^\|') {
            $cells = ($raw.Trim() -replace '^\||\|$', '') -split '\|'
            if (-not $inTable) {
                $inTable = $true
                $tableCells = $cells.Count
                $separator = if ($i + 1 -lt $lines.Count) { $lines[$i + 1] } else { '' }
                if ($separator -notmatch '^\|(\s*-{3,}\s*\|)+$') {
                    Fail "${name}:$($i + 2)" 'a table header is followed by a |---|---| separator without alignment markers'
                }
            } elseif ($cells.Count -ne $tableCells) {
                Fail $at "table row has $($cells.Count) cells, the header $tableCells (a pipe inside a cell?)"
            }
            if ($raw -notmatch '\|$') { Fail $at 'a table row ends with |' }
        } else {
            if ($inTable -and $raw -ne '') { Fail $at 'a table ends with a blank line' }
            $inTable = $false
        }

        # A rule needs a blank line before it (else it underlines a heading).
        if ($raw -eq '---' -and $previous -ne '') { Fail $at 'a --- rule needs a blank line before it' }

        # Pictures: alone on their line, under images/.
        $pictures = [regex]::Matches($line, '!\[([^\]]*)\]\(([^)]*)\)')
        foreach ($picture in $pictures) {
            if ($raw -notmatch '^!\[[^\]]+\]\(images/[a-z0-9-]+\.png\)$') {
                Fail $at 'a picture stands alone on its line as ![alt text](images/<page-id>-<what>.png)'
                continue
            }
            $file = $picture.Groups[2].Value.Substring('images/'.Length)
            [void]$shown.Add($file)
            $path = Join-Path (Join-Path $Wiki 'images') $file
            if (-not (Test-Path -LiteralPath $path)) {
                Fail $at "picture images/$file is missing"
                continue
            }
            $bytes = [System.IO.File]::ReadAllBytes($path)
            $png = $bytes.Length -gt 24 -and $bytes[0] -eq 0x89 -and $bytes[1] -eq 0x50 -and $bytes[2] -eq 0x4E -and $bytes[3] -eq 0x47
            if (-not $png) {
                Fail $at "images/$file is not a PNG"
                continue
            }
            $width = ([int]$bytes[16] -shl 24) -bor ([int]$bytes[17] -shl 16) -bor ([int]$bytes[18] -shl 8) -bor [int]$bytes[19]
            if ($width -gt $maxPictureWidth) { Fail $at "images/$file is $width px wide (at most $maxPictureWidth)" }
            if ($bytes.Length -gt $maxPictureBytes) {
                Fail $at "images/$file is $([math]::Round($bytes.Length / 1KB)) KB (at most $($maxPictureBytes / 1KB) KB)"
            }
        }

        # Links (pictures removed first).
        $text = [regex]::Replace($line, '!\[[^\]]*\]\([^)]*\)', '')
        foreach ($link in [regex]::Matches($text, '\[([^\]]*)\]\(([^)]*)\)')) {
            $target = $link.Groups[2].Value
            if ($link.Groups[1].Value.Trim() -eq '') { Fail $at "link to '$target' has no text" }
            if ($target -match '^https://\S+$') { continue }
            if ($target -match '^#([a-z0-9-]+)$') {
                if (-not $anchors[$id].Contains($Matches[1])) { Fail $at "no heading here for '#$($Matches[1])'" }
                continue
            }
            if ($target -match '^([a-z0-9-]+)\.md(#([a-z0-9-]+))?$') {
                $page = $Matches[1]
                $anchor = $Matches[3]
                if (-not $pages.ContainsKey($page)) {
                    Fail $at "link to a missing page '$page.md'"
                } elseif ($anchor -and -not $anchors[$page].Contains($anchor)) {
                    Fail $at "no heading in $page.md for '#$anchor'"
                }
                continue
            }
            Fail $at "link target '$target' is not page.md, page.md#anchor, #anchor or https://"
        }
        if ($text -match '\]\s+\(' ) { Warn $at 'a "]" followed by " (" : a broken link?' }
        $previous = $raw
    }

    # Under the title, a home link line ("[Home](home.md) · [Section](home.md#anchor)"), and at the end "## Related"
    # with two to four page links (not the home page: it is the index).
    if ($id -ne 'home') {
        # (The separator is a middle dot, U+00B7, spelled out so the script reads the same in any encoding.)
        $homeLine = '^\[Home\]\(home\.md\)( ' + [char]0xB7 + ' \[[^\]]+\]\(home\.md#[a-z0-9-]+\))?$'
        if ($lines.Count -lt 3 -or $lines[1] -ne '' -or $lines[2] -notmatch $homeLine) {
            Fail "${name}:3" 'the line under the title is "[Home](home.md) <middle dot> [Section](home.md#section)"'
        }
        $see = [Array]::LastIndexOf([string[]]$lines, '## Related')
        if ($see -lt 0) {
            Fail $name 'ends with a "## Related" list of related pages'
        } else {
            $rest = @($lines[($see + 1)..($lines.Count - 1)] | Where-Object { $_ -ne '' })
            $bad = @($rest | Where-Object { $_ -notmatch '^- \[[^\]]+\]\([a-z0-9-]+\.md(#[a-z0-9-]+)?\)$' })
            if ($rest.Count -lt 2 -or $rest.Count -gt 4 -or $bad.Count -gt 0) {
                Fail $name '"## Related" is last, followed only by two to four "- [Page](page.md)" lines'
            }
        }
    }
}

# Pictures on disk.
$imagesDir = Join-Path $Wiki 'images'
$total = 0
if (Test-Path -LiteralPath $imagesDir) {
    foreach ($picture in Get-ChildItem -LiteralPath $imagesDir -File) {
        $total += $picture.Length
        if ($picture.Name -notmatch '^([a-z0-9]+(-[a-z0-9]+)*)\.png$') {
            Fail "images/$($picture.Name)" 'pictures are <page-id>-<what>.png'
        } elseif (-not ($pages.PSBase.Keys | Where-Object { $picture.Name.StartsWith("$_-") })) {
            Fail "images/$($picture.Name)" 'the name starts with no page id'
        }
        if (-not $shown.Contains($picture.Name)) { Warn "images/$($picture.Name)" 'no page shows this picture' }
    }
}
if ($total -gt $picturesBudget * 1.1) {
    Fail 'images/' "pictures take $([math]::Round($total / 1MB, 2)) MB together (about $($picturesBudget / 1MB) MB budget)"
}

$script:warnings | ForEach-Object { Write-Host "warning: $_" }
$script:errors | ForEach-Object { Write-Host "error: $_" }
Write-Host ("Wiki check: {0} pages, {1} pictures ({2:N2} MB), {3} errors, {4} warnings" -f $pages.Count, $shown.Count,
    ($total / 1MB), $script:errors.Count, $script:warnings.Count)
if ($script:errors.Count -gt 0) { exit 1 }
exit 0
