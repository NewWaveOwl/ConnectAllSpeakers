<# Builds a user-mode DLL; no WDK, administrator access or driver installation required. #>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ResourceDirectory)
$ErrorActionPreference = 'Stop'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vsRoot = & $vswhere -version '[17.0,18.0)' -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsRoot) { throw 'Process capture requires VS2022 C++ x64 build tools and Windows SDK 26100.' }
$msbuild = Join-Path $vsRoot 'MSBuild/Current/Bin/amd64/MSBuild.exe'
& $msbuild (Join-Path $PSScriptRoot 'ProcessCapture.vcxproj') /t:Build /p:Configuration=Release /p:Platform=x64 /nologo /v:minimal
if ($LASTEXITCODE -ne 0) { throw 'Process capture DLL compilation failed.' }
$resourceRoot = Join-Path $ResourceDirectory 'win32-x86-64'
New-Item -ItemType Directory -Path $resourceRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build/AllConnectProcessCapture.dll') -Destination $resourceRoot -Force
