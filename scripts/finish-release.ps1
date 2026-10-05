# Completes a release by adding the Windows hashes to SHA256SUMS.txt.
#
# release.yml runs `sha256sum *.apk > SHA256SUMS.txt` and has an Android job and
# nothing else, so the published manifest has five lines and no MSI line. Without
# this step `publishedSha256` returns null, `expected` is null, and every desktop
# install reports `HandedOff(verified = false)` while installing unchecked.
#
# The APK lines are taken FROM THE RELEASE, never from `dist/`. That is the whole
# subtlety: `dist/` is whatever the owner's last local `wsl-build.sh` produced,
# while CI builds its own APKs on ubuntu. Appending locally computed APK hashes
# would publish a manifest whose APK lines match nothing, and every Android
# in-app update would then be refused by `apkRefusal`.
#
# Run after `git push origin v<version>`:
#     pwsh -File scripts\finish-release.ps1 -Version 2.3.2

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'OpenFluxPC\dist'
$tag = "v$Version"

# The version this script is told to finish must be the version the tree
# declares. Cutting a tag and publishing the MSI of a different build is exactly
# the divergence release.yml:72 exists to stop, and it cannot see this file.
$declared = (Select-String -Path (Join-Path $root 'gradle.properties') `
        -Pattern '^appVersion=(.*)$').Matches[0].Groups[1].Value.Trim()
if ($declared -ne $Version) {
    throw "gradle.properties says $declared, not $Version"
}

# Name the files explicitly. A `dist\*` glob would republish the stale 2.2.0 /
# 2.3.0 / 2.3.1 MSIs that OPERATIONS.md warns about.
$wanted = @("OpenFlux-$Version.msi", "OpenFlux-$Version-windows-amd64.zip")
$files = foreach ($name in $wanted) {
    $p = Join-Path $dist $name
    if (-not (Test-Path $p)) { throw "missing $p - run gradlew.bat :OpenFluxPC:collectDist first" }
    $p
}

$work = Join-Path $env:TEMP "of-release-$Version"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null

# Fetch CI's manifest. This is the authoritative one; we append to it.
& gh release download $tag --pattern SHA256SUMS.txt --dir $work --clobber
if ($LASTEXITCODE -ne 0) { throw "gh release download failed - is $tag published?" }

$manifest = Join-Path $work 'SHA256SUMS.txt'
$before = @(Get-Content $manifest)
if ($before.Count -ne 5) {
    throw "expected 5 APK lines from CI, found $($before.Count) - refusing to touch it"
}
$apkLines = $before | Where-Object { $_ -match 'OpenFluxAndroid-' }

foreach ($f in $files) {
    $hash = (Get-FileHash -Algorithm SHA256 $f).Hash.ToLower()
    # Two spaces, exactly like `sha256sum`, because that is what the client's
    # `\s+` split expects.
    Add-Content -Path $manifest -Encoding ascii -Value ("{0}  {1}" -f $hash, (Split-Path $f -Leaf))
}

$after = @(Get-Content $manifest)
if ($after.Count -ne 7) { throw "expected 7 lines after appending, found $($after.Count)" }
if (($after | Where-Object { $_ -match 'OpenFluxAndroid-' }) -join "`n" -ne $apkLines -join "`n") {
    throw "the APK lines changed - something rewrote CI's manifest"
}

# --clobber is required. release.yml:220 already uploaded this asset; without
# it the upload fails and the release keeps the manifest with no MSI line, which
# is indistinguishable from never having run this script.
& gh release upload $tag $manifest --clobber
if ($LASTEXITCODE -ne 0) { throw "gh release upload failed" }

Write-Host "SHA256SUMS.txt now has $($after.Count) lines for $tag"
$after | ForEach-Object { Write-Host "  $_" }
Write-Host "Desktop updates now verify the installer before msiexec."