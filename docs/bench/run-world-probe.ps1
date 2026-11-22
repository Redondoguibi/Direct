# Phase 2 world probe: a dev client with Direct (hybrid DX12 terrain off, its default), quick-playing a copy of a save.
# The DX12 backend mirrors every blaze3d draw (-Ddirect.phase1.shadow=true) and Dx12ShadowProbe walks fixed world
# scenes (-Ddirect.phase2.world=true), comparing OpenGL and DX12 pixel by pixel; images go to <case>/phase2.
# Run after gradlew assemble. ClasspathFile: run-case.ps1's clientcp. Source: a benchmark case directory with
# saves/<World>, options.txt and the mods the save needs (its Direct jar, if any, is replaced by the current build).
param(
    [Parameter(Mandatory)] [string] $ClasspathFile,
    [Parameter(Mandatory)] [string] $Source,
    [string] $World = "bench",
    [int] $Width = 1280,
    [int] $Height = 720,
    [int] $TimeoutSeconds = 600,
    [switch] $DebugLayer,
    [switch] $Trace,
    [switch] $Present,
    [switch] $SkipGl,
    [switch] $VerifyMeshes,
    [switch] $DirectTerrain,
    [switch] $Pack,
    [string] $PackName = "",
    [switch] $Dx12Translucency,
    [switch] $Hidden
)
$ErrorActionPreference = "Stop"
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$moddev = Join-Path $project 'build/moddev'
$clientcp = (Resolve-Path -LiteralPath $ClasspathFile).Path
$version = ((Get-Content -LiteralPath "$project/gradle.properties" | Select-String '^mod_version=(.+)$').Matches.Groups[1].Value)
$jar = Get-Item -LiteralPath "$project/build/libs/direct-$version.jar"
$case = Join-Path $project ("build/phase2-world/" + [guid]::NewGuid().ToString("N"))
$mods = New-Item -ItemType Directory -Path "$case/mods" -Force
# -Pack: Raydiance and its configuration (shader pack and options) from the source case too.
Get-ChildItem -LiteralPath "$Source/mods" -Filter *.jar | Where-Object { $_.Name -notlike 'direct-*' -and ($Pack -or $_.Name -notlike 'raydiance*') } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $mods.FullName }
Copy-Item -LiteralPath $jar.FullName -Destination $mods.FullName
New-Item -ItemType Directory -Path "$case/saves" -Force | Out-Null
Copy-Item -LiteralPath "$Source/saves/$World" -Destination "$case/saves/$World" -Recurse
# Same options as the source, windowed, never pausing on focus loss.
$options = Get-Content -LiteralPath "$Source/options.txt" | Where-Object { $_ -notmatch '^(fullscreen|pauseOnLostFocus|onboardAccessibility):' }
($options + @('fullscreen:false', 'pauseOnLostFocus:false', 'onboardAccessibility:false')) |
    Set-Content -LiteralPath "$case/options.txt" -Encoding ascii
# -DirectTerrain: Direct's DX12 terrain replaces vanilla terrain (the hybrid mode); the backend composites it itself.
New-Item -ItemType Directory -Path "$case/config" -Force | Out-Null
if ($Pack) {
    Copy-Item -LiteralPath "$Source/config/raydiance-client.toml" -Destination "$case/config/"
    Copy-Item -LiteralPath "$Source/config/raydiance" -Destination "$case/config/raydiance" -Recurse
    if ($PackName) {
        # Without a byte order mark: the TOML parser rejects one, and the file would be recreated with defaults.
        $lines = (Get-Content -LiteralPath "$case/config/raydiance-client.toml") -replace '^openglPack = .*$', "openglPack = `"$PackName`""
        [System.IO.File]::WriteAllLines("$case/config/raydiance-client.toml", $lines, (New-Object System.Text.UTF8Encoding($false)))
    }
}
@("enableDx12Preview = $(if ($DirectTerrain) { 'true' } else { 'false' })",
  "dx12Translucency = $(if ($Dx12Translucency) { 'true' } else { 'false' })") | Set-Content -LiteralPath "$case/config/direct-client.toml" -Encoding ascii
$jvmArgs = @('-Xmx3G', '-Ddirect.phase1.shadow=true', '-Ddirect.phase1.probe=true', '-Ddirect.phase2.world=true', "@`"$clientcp`"",
    "@`"$moddev/clientRunVmArgs.txt`"", 'net.neoforged.devlaunch.Main', "@`"$moddev/clientRunProgramArgs.txt`"",
    '--quickPlaySingleplayer', $World, '--width', "$Width", '--height', "$Height", '--username', 'Dev')
if ($DebugLayer) { $jvmArgs = @('-Ddirect.phase1.debug=true') + $jvmArgs }
if ($Trace) { $jvmArgs = @('-Ddirect.phase1.trace=true') + $jvmArgs }
if ($Present) { $jvmArgs = @('-Ddirect.phase1.present=true') + $jvmArgs }
if ($SkipGl) { $jvmArgs = @('-Ddirect.phase1.skipGl=true') + $jvmArgs }
if ($VerifyMeshes) { $jvmArgs = @('-Ddirect.phase2.verifyMeshes=true') + $jvmArgs }
$client = Start-Process java -WorkingDirectory $case -WindowStyle Hidden -ArgumentList $jvmArgs -PassThru `
    -RedirectStandardOutput "$case/client.out" -RedirectStandardError "$case/client.err"
$null = $client.Handle
Add-Type -Namespace DirectBench -Name WorldWindow -MemberDefinition @"
[DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr handle, int cmd);
[DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback, IntPtr data);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr handle, out uint pid);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr handle);
public delegate bool Callback(IntPtr handle, IntPtr data);
public static void Hide(uint pid) { EnumWindows((h, d) => { uint id; GetWindowThreadProcessId(h, out id); if (id == pid && IsWindowVisible(h)) ShowWindowAsync(h, 0); return true; }, IntPtr.Zero); }
"@ -ErrorAction SilentlyContinue
$end = (Get-Date).AddSeconds($TimeoutSeconds)
# Present mode checks the desktop, so its window stays visible unless -Hidden (no desktop check then).
while (!$client.HasExited -and (Get-Date) -lt $end) { if (!$Present -or $Hidden) { [DirectBench.WorldWindow]::Hide($client.Id) }; Start-Sleep -Milliseconds 250 }
if (!$client.HasExited) { $client.Kill(); Write-Output "Timed out" }
$log = "$case/logs/latest.log"
Write-Output "Results in $case"
Select-String -LiteralPath $log -Pattern 'Direct phase(1|2) (shadow|present|world)' | ForEach-Object { $_.Line }
