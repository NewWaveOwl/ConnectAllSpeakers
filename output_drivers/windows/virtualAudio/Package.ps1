<# Creates a validated, unsigned catalog package. Does not sign or install it. #>
[CmdletBinding()]
param([ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
& (Join-Path $PSScriptRoot 'Build.ps1') -Configuration $Configuration
$outputRoot = Join-Path $PSScriptRoot "build/x64/$Configuration"
$packageRoot = Join-Path $outputRoot 'package'
New-Item -ItemType Directory -Path $packageRoot -Force | Out-Null
# A previous catalog must not survive a failed attempt with new binaries.
$catalogPath = Join-Path $packageRoot 'AllConnectVirtualAudio.cat'
if (Test-Path -LiteralPath $catalogPath) { Remove-Item -LiteralPath $catalogPath }
foreach ($file in @('AllConnectVirtualAudio.sys', 'AllConnectVirtualAudio.inf')) {
    Copy-Item -LiteralPath (Join-Path $outputRoot $file) -Destination $packageRoot -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'NOTICE.md') -Destination $packageRoot -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'vendor/sysvad/LICENSE') -Destination $packageRoot -Force
$kitsRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10'
$infVerif = Join-Path $kitsRoot 'Tools/10.0.26100.0/x64/InfVerif.exe'
$inf2Cat = Join-Path $kitsRoot 'bin/10.0.26100.0/x86/Inf2Cat.exe'
& $infVerif /u (Join-Path $packageRoot 'AllConnectVirtualAudio.inf')
if ($LASTEXITCODE -ne 0) { throw "INF validation failed ($LASTEXITCODE)." }
& $inf2Cat "/driver:$packageRoot" /os:10_GE_X64 /uselocaltime
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $catalogPath)) { throw 'Catalog generation failed.' }
Get-ChildItem -LiteralPath $packageRoot -File | Get-FileHash -Algorithm SHA256 |
    Select-Object Hash, Path
Write-Output "Unsigned package: $packageRoot. Signing and explicit lab installation are still required."
