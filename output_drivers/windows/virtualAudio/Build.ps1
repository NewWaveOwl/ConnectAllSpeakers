<#
.SYNOPSIS
Verifies vendored source and builds the unsigned x64 prototype. Never installs a driver.
.PARAMETER PrepareOnly
Verify source hashes and prepare reviewed non-sideband source variants without needing WDK/MSVC.
.PARAMETER Configuration
Debug or Release driver configuration.
.PARAMETER CheckTools
Report every missing build prerequisite without writing sources or building.
#>
[CmdletBinding()]
param([switch]$PrepareOnly, [switch]$CheckTools, [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PrepareOnly -and $CheckTools) { throw 'Choose either -PrepareOnly or -CheckTools.' }
$driverRoot = $PSScriptRoot

function Get-DriverBuildEnvironment {
    $kitsRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10'
    $kitVersion = '10.0.26100.0'
    $required = @("Include/$kitVersion/km/portcls.h", "Include/$kitVersion/km/ntddk.h",
        "Lib/$kitVersion/km/x64/portcls.lib", "Lib/$kitVersion/km/x64/ntoskrnl.lib", 'Include/wdf/kmdf/1.33/wdf.h')
    $problems = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $kitsRoot $_)) } |
        ForEach-Object { "Missing WDK/KMDF file: $_" })
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $vsRoot = $null
    if (Test-Path -LiteralPath $vswhere) {
        $vsRoot = & $vswhere -version '[17.0,18.0)' -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    }
    $msbuild = $null
    if (-not $vsRoot) {
        $problems += 'Missing Visual Studio 2022 C++ x64 build tools.'
    } else {
        $msbuild = Join-Path $vsRoot 'MSBuild/Current/Bin/amd64/MSBuild.exe'
        if (-not (Test-Path -LiteralPath $msbuild)) { $problems += 'Missing MSBuild in the selected Visual Studio installation.' }
        $driverProps = Join-Path $vsRoot 'MSBuild/Microsoft/VC/v170/Platforms/x64/PlatformToolsets/WindowsKernelModeDriver10.0/Toolset.props'
        if (-not (Test-Path -LiteralPath $driverProps)) { $problems += 'Missing Visual Studio 2022 WDK integration (WindowsKernelModeDriver10.0).' }
    }
    [pscustomobject]@{ Problems = @($problems); MSBuild = $msbuild }
}

if ($CheckTools -or -not $PrepareOnly) {
    $environment = Get-DriverBuildEnvironment
    if ($environment.Problems.Count -gt 0) {
        throw "Driver build prerequisites missing:`n$($environment.Problems -join "`n")`nUse matching SDK/WDK build 26100, KMDF 1.33 and Visual Studio 2022 C++/WDK tools. No driver was installed."
    }
    if ($CheckTools) { Write-Output 'Driver build prerequisites found. Compilation, INF validation, signing and installation are separate checks.'; return }
}
$vendorRoot = Join-Path $driverRoot 'vendor/sysvad'
$manifest = Get-Content -LiteralPath (Join-Path $vendorRoot 'upstream.json') -Raw | ConvertFrom-Json
foreach ($entry in $manifest.files) {
    $sourcePath = Join-Path $vendorRoot $entry.path
    if ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Vendored source differs from pinned upstream: $($entry.path)"
    }
}
$adapter = Get-Content -LiteralPath (Join-Path $vendorRoot 'adapter.cpp') -Raw
# Upstream defaults data-file recording off but permits a registry override. Remove
# that override entirely: this build cannot enable sample PCM recording via registry.
$overridePattern = '(?m)^.*RTL_QUERY_REGISTRY_DIRECT.*L"DoNotCreateDataFiles"[^\r\n]*\r?\n'
if ([regex]::Matches($adapter, $overridePattern).Count -ne 1) { throw 'Pinned adapter recording override changed; review before building.' }
$adapter = [regex]::Replace($adapter, $overridePattern, '')
$preparedRoot = Join-Path $driverRoot 'build/sources'
New-Item -ItemType Directory -Path $preparedRoot -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $preparedRoot 'adapter.cpp'), $adapter, [Text.UTF8Encoding]::new($false))
# The pinned upstream engine-node file references sideband members even when
# those optional backends are disabled. Keep only its eight ordinary-device
# branches in the generated copy; never change the verified vendor sources.
$engineNode = Get-Content -LiteralPath (Join-Path $vendorRoot 'EndpointsCommon/MiniportAudioEngineNode.cpp') -Raw
$sidebandPattern = 'if \(IsSidebandDevice\(\) && m_pSidebandDevice->Is(?:Volume|Mute)Supported\(m_DeviceType\)\)'
$branches = [regex]::Matches($engineNode, $sidebandPattern)
if ($branches.Count -ne 8) { throw 'Pinned sideband branches changed; review before building.' }
for ($branchIndex = $branches.Count - 1; $branchIndex -ge 0; $branchIndex--) {
    $branch = $branches[$branchIndex]
    $cursor = $engineNode.IndexOf('{', $branch.Index + $branch.Length)
    $depth = 1
    for ($cursor++; $cursor -lt $engineNode.Length -and $depth -gt 0; $cursor++) {
        if ($engineNode[$cursor] -eq '{') { $depth++ }
        elseif ($engineNode[$cursor] -eq '}') { $depth-- }
    }
    $elseToken = [regex]::Match($engineNode.Substring($cursor), '^\s*else\s*(?=\{)')
    if ($depth -ne 0 -or (-not $elseToken.Success -and $branchIndex -ne 7)) { throw 'Expected sideband fallback block; review pinned source.' }
    $engineNode = $engineNode.Remove($branch.Index, $cursor + $elseToken.Length - $branch.Index)
}
[IO.File]::WriteAllText((Join-Path $preparedRoot 'MiniportAudioEngineNode.cpp'), $engineNode, [Text.UTF8Encoding]::new($false))
Write-Output "Verified $($manifest.files.Count) source files at $($manifest.revision); prepared adapter with recording override removed."
if ($PrepareOnly) { return }

$msbuild = $environment.MSBuild
& $msbuild (Join-Path $driverRoot 'AllConnectVirtualAudio.vcxproj') /m /t:Build "/p:Configuration=$Configuration" /p:Platform=x64 /p:SignMode=Off /p:EnableInf2Cat=false /nologo
if ($LASTEXITCODE -ne 0) { throw "Native driver build failed (exit $LASTEXITCODE)." }
Write-Output 'Unsigned build only. Review the driver lab checklist before creating a catalog, signing or installing it.'
