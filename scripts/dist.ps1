# Builds the release jars (the files players download from the stores) and keeps the newest of each product in
# .local\dist, the earlier ones under .local\dist\archive\<product>\ with their names unchanged.
#   scripts/dist.ps1                       # all three products: Fabric, NeoForge, Paper
#   scripts/dist.ps1 -Products fabric,paper
#   scripts/dist.ps1 -NoBuild              # only collect what modules\<product>\build\libs already holds
# A release jar is named by the product's version in gradle.properties and the Minecraft version alone
# (sculptory-fabric-0.5.0-beta+1.21.1.jar): what the release workflow builds from a tag, built here with
# -Prelease=<product> (root build.gradle). Gradle refuses a -dev version; set the release version first.
param(
    [string[]]$Products = @('fabric', 'neoforge', 'paper'),
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tasks = @{ fabric = ':fabric:remapJar'; neoforge = ':neoforge:jar'; paper = ':paper:jar' }
$Products = @($Products | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
foreach ($product in $Products) {
    if (-not $tasks.ContainsKey($product)) { throw "Unknown product '$product': fabric, neoforge or paper." }
}

$dist = Join-Path $root '.local\dist'
New-Item -ItemType Directory -Force $dist | Out-Null

foreach ($product in $Products) {
    if (-not $NoBuild) {
        Write-Host "Building the $product release jar..."
        & (Join-Path $PSScriptRoot 'gradle.ps1') $tasks[$product] "-Prelease=$product" '--console=plain' '--quiet'
        if ($LASTEXITCODE -ne 0) { throw "The $product build failed (exit $LASTEXITCODE)." }
    }
    $libs = Join-Path $root "modules\$product\build\libs"
    # The release jar: no commit in its name (a dev build adds one, sculptory-fabric-0.5.0-dev+1.21.1.6a043a85.jar),
    # and not Paper's -dev.jar, which carries the self-test.
    $jar = Get-ChildItem -Path $libs -Filter "sculptory-$product-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-dev.jar' -and $_.Name -match "^sculptory-$product-[^+]+\+\d+(\.\d+)*\.jar$" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        if ($NoBuild) { Write-Host "No $product release jar in $libs (run without -NoBuild)."; continue }
        throw "The $product build made no release jar in $libs."
    }
    # Earlier jars of this product move to the archive; the same name is replaced in place.
    $archive = Join-Path $dist "archive\$product"
    Get-ChildItem -Path $dist -Filter "sculptory-$product-*.jar" -File | Where-Object { $_.Name -ne $jar.Name } |
        ForEach-Object {
            New-Item -ItemType Directory -Force $archive | Out-Null
            Move-Item -LiteralPath $_.FullName -Destination (Join-Path $archive $_.Name) -Force
            Write-Host "Archived $($_.Name) -> archive\$product"
        }
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $dist $jar.Name) -Force
    Write-Host "${product}: $($jar.Name) ($([math]::Round($jar.Length / 1MB, 1)) MB)"
}
Write-Host "Newest jars: $dist"
