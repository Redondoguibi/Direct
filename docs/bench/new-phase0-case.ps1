# Fresh copies of the scene and comparison stacks prepared for run-case.ps1.
param([Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][string]$Label,
    [ValidateSet("direct","sodium")][string]$Stack, [string]$DirectJar)
$ErrorActionPreference = "Stop"
if ($Label -notmatch '^[a-zA-Z0-9_-]+$') { throw "Invalid case label" }
$caseDir = Join-Path $Root $Label
if (Test-Path -LiteralPath $caseDir) { throw "Case already exists: $caseDir" }
$template = Join-Path $Root template
$overlay = Join-Path $Root $(if ($Stack -eq "direct") { "stack-direct-191" } else { "stack-sodium" })
if (!(Test-Path "$template/saves/bench/level.dat") -or !(Test-Path "$overlay/mods")) { throw "Missing benchmark fixtures" }
if ($Stack -eq "direct" -and !(Test-Path -LiteralPath $DirectJar -PathType Leaf)) { throw "DirectJar is required" }
Copy-Item -LiteralPath $template -Destination $caseDir -Recurse
foreach ($folder in (Get-ChildItem -LiteralPath $overlay -Directory)) {
    $dest = Join-Path $caseDir $folder.Name
    [IO.Directory]::CreateDirectory($dest) | Out-Null
    Get-ChildItem -LiteralPath $folder.FullName | Where-Object { !($Stack -eq "direct" -and $_.Name -like 'direct-*.jar') } |
        Copy-Item -Destination $dest -Recurse -Force
}
if ($Stack -eq "direct") {
    Copy-Item -LiteralPath $DirectJar -Destination "$caseDir/mods"
    $options = Join-Path $caseDir options.txt
    (Get-Content $options) -replace '^renderClouds:.*$', 'renderClouds:"false"' | Set-Content $options -Encoding ascii
}
if (Test-Path "$caseDir/logs/bench-fps.csv") { throw "Template contains an old CSV" }
Write-Output $caseDir
