# Runs one benchmark case: a NeoForge 21.1.265 dev client (same launcher and JVM for every renderer stack) in
# $Root\$Label, quick-playing saves\$World, hidden window 1920x1080, until logs\bench-fps.csv holds $Windows rows of
# 60 s (written by the test-only benchfps mod), then closes the game normally. Uses the moddev files under build\moddev.
param(
    [Parameter(Mandatory)] [string] $Root,
    [Parameter(Mandatory)] [string] $Label,
    [string] $World = "bench",
    [int] $Windows = 3,
    [int] $TimeoutMinutes = 7
)
$ErrorActionPreference = "Stop"
$direct = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$moddev = Join-Path $direct "build\moddev"
$case = Join-Path $Root $Label
if (!(Test-Path (Join-Path $case "mods"))) { throw "Missing case directory $case" }
$classpath = Join-Path $Root "clientcp"
$arguments = @('-Xmx3G', "@$classpath", "@`"$moddev\clientRunVmArgs.txt`"", 'net.neoforged.devlaunch.Main',
    "@`"$moddev\clientRunProgramArgs.txt`"", '--quickPlaySingleplayer', $World, '--width', '1920', '--height', '1080',
    '--username', 'Dev')
$game = Start-Process java -WindowStyle Hidden -ArgumentList $arguments -WorkingDirectory $case `
    -RedirectStandardOutput "$case.out" -RedirectStandardError "$case.err" -PassThru

Add-Type -Namespace DirectBench -Name Window -MemberDefinition @"
[DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr handle, int cmd);
[DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window, uint msg, IntPtr w, IntPtr l);
[DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback, IntPtr data);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr handle, out uint pid);
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr handle);
public delegate bool Callback(IntPtr handle, IntPtr data);
public static void Hide(uint pid) { EnumWindows((h, d) => { uint id; GetWindowThreadProcessId(h, out id); if (id == pid && IsWindowVisible(h)) ShowWindowAsync(h, 0); return true; }, IntPtr.Zero); }
public static void Close(uint pid) { EnumWindows((h, d) => { uint id; GetWindowThreadProcessId(h, out id); if (id == pid) PostMessage(h, 0x10, IntPtr.Zero, IntPtr.Zero); return true; }, IntPtr.Zero); }
"@ -ErrorAction SilentlyContinue

$csv = Join-Path $case "logs\bench-fps.csv"
$end = (Get-Date).AddMinutes($TimeoutMinutes)
while (!$game.HasExited -and (Get-Date) -lt $end) {
    [DirectBench.Window]::Hide($game.Id)
    if ((Test-Path $csv) -and @(Get-Content $csv).Length -ge ($Windows + 1)) { break }
    Start-Sleep -Milliseconds 250
}
if (!$game.HasExited) {
    [DirectBench.Window]::Close($game.Id)
    if (!$game.WaitForExit(30000)) { Stop-Process -Id $game.Id -Force }
}
$rows = if (Test-Path $csv) { @(Get-Content $csv).Length - 1 } else { 0 }
"$Label finished $(Get-Date -Format HH:mm:ss), windows=$rows"
