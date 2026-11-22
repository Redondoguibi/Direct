# Phase 1 shadow probe: a dev client with only Direct, -Ddirect.phase1.shadow=true and -Ddirect.phase1.probe=true.
# OpenGL renders the menus as usual; the DX12 backend replays every draw. Each listed screen is compared pixel by pixel
# (images in <case>/phase1) and the client stops. Run after gradlew assemble. ClasspathFile: run-case.ps1's clientcp.
param(
    [Parameter(Mandatory)] [string] $ClasspathFile,
    [int] $Width = 1280,
    [int] $Height = 720,
    [int] $TimeoutSeconds = 300,
    [switch] $DebugLayer,
    [switch] $Trace,
    [switch] $Present,
    [switch] $SkipGl
)
$ErrorActionPreference = "Stop"
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$moddev = Join-Path $project 'build/moddev'
$clientcp = (Resolve-Path -LiteralPath $ClasspathFile).Path
$version = ((Get-Content -LiteralPath "$project/gradle.properties" | Select-String '^mod_version=(.+)$').Matches.Groups[1].Value)
$jar = Get-Item -LiteralPath "$project/build/libs/direct-$version.jar"
$case = Join-Path $project ("build/phase1-shadow/" + [guid]::NewGuid().ToString("N"))
$mods = New-Item -ItemType Directory -Path "$case/mods" -Force
Copy-Item -LiteralPath $jar.FullName -Destination $mods.FullName
@'
fullscreen:false
onboardAccessibility:false
pauseOnLostFocus:false
'@ | Set-Content -LiteralPath "$case/options.txt" -Encoding ascii
$jvmArgs = @('-Xmx3G', '-Ddirect.phase1.shadow=true', '-Ddirect.phase1.probe=true', "@`"$clientcp`"",
    "@`"$moddev/clientRunVmArgs.txt`"", 'net.neoforged.devlaunch.Main', "@`"$moddev/clientRunProgramArgs.txt`"",
    '--width', "$Width", '--height', "$Height", '--username', 'Dev')
if ($DebugLayer) { $jvmArgs = @('-Ddirect.phase1.debug=true') + $jvmArgs }
if ($Trace) { $jvmArgs = @('-Ddirect.phase1.trace=true') + $jvmArgs }
# Present mode checks the desktop, so its window stays visible.
if ($Present) { $jvmArgs = @('-Ddirect.phase1.present=true') + $jvmArgs }
if ($SkipGl) { $jvmArgs = @('-Ddirect.phase1.skipGl=true') + $jvmArgs }
$client = Start-Process java -WorkingDirectory $case -WindowStyle Hidden -ArgumentList $jvmArgs -PassThru `
    -RedirectStandardOutput "$case/client.out" -RedirectStandardError "$case/client.err"
$null = $client.Handle
Add-Type -Namespace DirectBench -Name ShadowWindow -MemberDefinition @"
[DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr handle, int cmd);
[DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback, IntPtr data);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr handle, out uint pid);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr handle);
public delegate bool Callback(IntPtr handle, IntPtr data);
public static void Hide(uint pid) { EnumWindows((h, d) => { uint id; GetWindowThreadProcessId(h, out id); if (id == pid && IsWindowVisible(h)) ShowWindowAsync(h, 0); return true; }, IntPtr.Zero); }
"@ -ErrorAction SilentlyContinue
$end = (Get-Date).AddSeconds($TimeoutSeconds)
while (!$client.HasExited -and (Get-Date) -lt $end) { if (!$Present) { [DirectBench.ShadowWindow]::Hide($client.Id) }; Start-Sleep -Milliseconds 250 }
if (!$client.HasExited) { $client.Kill(); Write-Output "Timed out" }
$log = "$case/logs/latest.log"
Write-Output "Results in $case"
Select-String -LiteralPath $log -Pattern 'Direct phase1 (shadow|present)' | ForEach-Object { $_.Line }
