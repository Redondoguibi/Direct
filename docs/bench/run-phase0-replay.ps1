param([Parameter(Mandatory)][string]$Scene)
$ErrorActionPreference = "Stop"
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$classes = "$repo/build/classes/java"
$dll = "$repo/build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll"
& java -cp "$classes/main;$classes/test" com.redondoguibi.direct.Phase0Replay $dll $Scene
if ($LASTEXITCODE -ne 0) { throw "Phase 0 replay failed ($LASTEXITCODE)" }
