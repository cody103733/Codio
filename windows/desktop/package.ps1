# Builds Codio for Windows on Windows: the web UI plus Codio.exe into dist\Codio-win64,
# then the installer dist\CodioSetup-<version>.exe with NSIS.
#
#   -ShellExe    Codio.exe built from windows\shell (see windows\shell\README.md)
#   -LoaderDll   WebView2Loader.dll (x64), for example from the webview2-com-sys crate
#   -RuntimeDir  a folder with libmpv-2.dll (mpv)
#   -NodeExe     an official Node.js node.exe (win-x64); shipped as codio-runtime.exe
#   -Makensis    NSIS's makensis.exe
#   -SkipWebBuild  reuse an existing build\ folder
param(
    [Parameter(Mandatory)] [string]$ShellExe,
    [Parameter(Mandatory)] [string]$LoaderDll,
    [Parameter(Mandatory)] [string]$RuntimeDir,
    [Parameter(Mandatory)] [string]$NodeExe,
    [Parameter(Mandatory)] [string]$Makensis,
    [Parameter(Mandatory)] [string]$Version,
    [switch]$SkipWebBuild
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = Split-Path -Parent $here
Set-Location $repo

if (-not $SkipWebBuild) {
    $env:CODIO_VERSION = $Version
    & pnpm build
    if ($LASTEXITCODE) { throw 'pnpm build failed' }
}

$out = Join-Path $repo 'dist\Codio-win64'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item -Recurse (Join-Path $repo 'build') (Join-Path $out 'web')
Copy-Item (Join-Path $here 'codio-web.js'), (Join-Path $here 'codio-downloads.js'), (Join-Path $here 'codio-update.js'), (Join-Path $here 'codio.ico'), (Join-Path $here 'README.txt') $out
Copy-Item $ShellExe (Join-Path $out 'Codio.exe')
Copy-Item $LoaderDll (Join-Path $out 'WebView2Loader.dll')
Copy-Item $NodeExe (Join-Path $out 'codio-runtime.exe')
Get-ChildItem $RuntimeDir -File | Where-Object { $_.Extension -in '.exe', '.dll' } | Copy-Item -Destination $out
if (-not (Test-Path (Join-Path $out 'libmpv-2.dll'))) { throw 'libmpv-2.dll is missing from -RuntimeDir' }

$nsi = Join-Path $here 'installer\codio.nsi'
& $Makensis -V2 "-DVERSION=$Version" "-DSRC=$out" $nsi
if ($LASTEXITCODE) { throw 'makensis failed' }
Move-Item -Force (Join-Path $here "installer\CodioSetup-$Version.exe") (Join-Path $repo 'dist')
Write-Output "Created dist\CodioSetup-$Version.exe"
