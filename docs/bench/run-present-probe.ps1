# Run after gradlew jar prepareClientRun. ClasspathFile is the clientcp argument file used by run-case.ps1.
param(
    [Parameter(Mandatory)] [string] $ClasspathFile,
    [string] $Java = "java",
    [int] $TimeoutSeconds = 240
)
$ErrorActionPreference = "Stop"
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$moddev = Join-Path $project 'build/moddev'
$clientcp = (Resolve-Path -LiteralPath $ClasspathFile).Path
$version = ((Get-Content -LiteralPath "$project/gradle.properties" | Select-String '^mod_version=(.+)$').Matches.Groups[1].Value)
$jar = Get-Item -LiteralPath "$project/build/libs/direct-$version.jar"
$case = Join-Path $project ("build/phase0-present/" + [guid]::NewGuid().ToString("N"))
$mods = New-Item -ItemType Directory -Path "$case/mods" -Force
$null = New-Item -ItemType Directory -Path "$case/config"
Copy-Item -LiteralPath $jar.FullName -Destination $mods.FullName
@'
earlyWindowControl = true
earlyWindowProvider = "fmlearlywindow"
earlyWindowWidth = 960
earlyWindowHeight = 540
earlyWindowMaximized = false
'@ | Set-Content -LiteralPath "$case/config/fml.toml" -Encoding ascii
@'
fullscreen:false
onboardAccessibility:false
'@ | Set-Content -LiteralPath "$case/options.txt" -Encoding ascii
$jvmArgs = @('-Xmx3G', '-Ddirect.phase0.present=true', "@`"$clientcp`"",
    "@`"$moddev/clientRunVmArgs.txt`"", 'net.neoforged.devlaunch.Main', "@`"$moddev/clientRunProgramArgs.txt`"",
    '--width', '960', '--height', '540', '--username', 'Dev')
Write-Output "Windowed and fullscreen DXGI presentation probe. Results in $case"
$client = Start-Process $Java -WorkingDirectory $case -WindowStyle Hidden -ArgumentList $jvmArgs -PassThru `
    -RedirectStandardOutput "$case/client.out" -RedirectStandardError "$case/client.err"
# Windows PowerShell 5.1 reports a null ExitCode unless the process handle is opened while the process runs.
$null = $client.Handle
if (!$client.WaitForExit($TimeoutSeconds * 1000)) {
    $client.Kill()
    throw "Presentation probe timed out. See $case"
}
$log = "$case/logs/latest.log"
Select-String -LiteralPath $log -Pattern 'Loading ImmediateWindowProvider','Requested GL version','Direct phase0 presentation' |
    ForEach-Object { $_.Line }
if ($client.ExitCode -ne 0 -or !(Select-String -Quiet -LiteralPath $log -Pattern 'Loading ImmediateWindowProvider fmlearlywindow') -or
    !(Select-String -Quiet -LiteralPath $log -Pattern 'Direct phase0 presentation PASS')) {
    throw "Presentation probe failed. See $log"
}
