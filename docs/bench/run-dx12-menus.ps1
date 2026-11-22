# Opens a dev client whose menus are rendered only by the phase 1 DX12 backend and shown through DXGI
# (-Ddirect.phase1.shadow/present/skipGl). OpenGL draws are cancelled. For manual navigation; close the game to end.
# Run after gradlew assemble. ClasspathFile: run-case.ps1's clientcp. Entering a world is phase 2 and is not supported.
param(
    [Parameter(Mandatory)] [string] $ClasspathFile,
    [int] $Width = 1280,
    [int] $Height = 720
)
$ErrorActionPreference = "Stop"
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$moddev = Join-Path $project 'build/moddev'
$clientcp = (Resolve-Path -LiteralPath $ClasspathFile).Path
$version = ((Get-Content -LiteralPath "$project/gradle.properties" | Select-String '^mod_version=(.+)$').Matches.Groups[1].Value)
$jar = Get-Item -LiteralPath "$project/build/libs/direct-$version.jar"
$case = Join-Path $project ("build/phase1-menus/" + [guid]::NewGuid().ToString("N"))
$mods = New-Item -ItemType Directory -Path "$case/mods" -Force
Copy-Item -LiteralPath $jar.FullName -Destination $mods.FullName
"onboardAccessibility:false" | Set-Content -LiteralPath "$case/options.txt" -Encoding ascii
$jvmArgs = @('-Xmx3G', '-Ddirect.phase1.shadow=true', '-Ddirect.phase1.present=true', '-Ddirect.phase1.skipGl=true',
    "@`"$clientcp`"", "@`"$moddev/clientRunVmArgs.txt`"", 'net.neoforged.devlaunch.Main',
    "@`"$moddev/clientRunProgramArgs.txt`"", '--width', "$Width", '--height', "$Height", '--username', 'Dev')
Start-Process java -WorkingDirectory $case -ArgumentList $jvmArgs -RedirectStandardOutput "$case/client.out" `
    -RedirectStandardError "$case/client.err" | Out-Null
Write-Output "DX12 menus running. Log: $case/logs/latest.log"
