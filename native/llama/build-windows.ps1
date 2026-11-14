param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$Version,
    [Parameter(Mandatory=$true)][string]$WorkDirectory,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$CMake,
    [string]$ToolchainBin,
    [string]$Jdk = $env:JAVA_HOME,
    [ValidateRange(1,16)][int]$Jobs = 2,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$Jdk -or !(Test-Path -LiteralPath (Join-Path $Jdk 'include/jni.h'))) { throw 'A JDK with JNI headers is required' }

# Prefer explicit settings, then PATH, then the tools already prepared in this checkout.
if (!$ToolchainBin) {
    $compiler = Get-Command gcc.exe -ErrorAction SilentlyContinue
    if ($compiler) { $ToolchainBin = Split-Path $compiler.Source }
    else { $ToolchainBin = Join-Path $repo 'build/embedded-llama/w64devkit/bin' }
}
$ToolchainBin = [IO.Path]::GetFullPath($ToolchainBin)
foreach ($tool in @('gcc.exe', 'g++.exe', 'make.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $ToolchainBin $tool))) {
        throw 'MinGW-w64 gcc/g++/make are required. Put w64devkit on PATH or set -Pllama_toolchain_bin=<bin directory>.'
    }
}
if (!$CMake) {
    $command = Get-Command cmake.exe -ErrorAction SilentlyContinue
    if ($command) { $CMake = $command.Source }
    else { $CMake = Join-Path $ToolchainBin 'cmake.exe' }
}
if (!(Test-Path -LiteralPath $CMake)) { throw 'CMake 3.24+ is required. Put it on PATH or set -Pllama_cmake=<cmake.exe>.' }
$work = Join-Path ([IO.Path]::GetFullPath($WorkDirectory)) $Version
$source = Join-Path $work 'source'
$archive = Join-Path $work 'source.tar.gz'
$ready = Join-Path $source '.smartnpc-source-ready'
New-Item -ItemType Directory -Force $work | Out-Null
if (!(Test-Path -LiteralPath $ready)) {
    if (!(Test-Path -LiteralPath $archive)) {
        if ($Offline) { throw "llama.cpp $Version is not cached; run once without --offline to download it." }
        $partial = "$archive.part"
        try {
            Write-Output "Downloading upstream llama.cpp $Version"
            Invoke-WebRequest "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/$Version" -OutFile $partial
            Move-Item -LiteralPath $partial -Destination $archive -Force
        } finally {
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        }
    }
    New-Item -ItemType Directory -Force $source | Out-Null
    & tar.exe -xzf $archive --strip-components=1 -C $source
    if ($LASTEXITCODE) { throw "Cannot extract llama.cpp $Version. Remove the cached source.tar.gz and retry." }
    if (!(Test-Path -LiteralPath (Join-Path $source 'CMakeLists.txt'))) { throw 'Upstream source archive is incomplete' }
    Set-Content -LiteralPath $ready -Value $Version -Encoding ascii
}
$env:Path = "$ToolchainBin;" + $env:Path
# Version-specific source/CMake directories prevent reuse across incompatible upstream releases.
# Native stderr contains normal CMake diagnostics; use exit codes to detect failures.
$ErrorActionPreference = 'Continue'
& $CMake -S $PSScriptRoot -B (Join-Path $work 'compile') -G 'Unix Makefiles' "-DCMAKE_MAKE_PROGRAM=$ToolchainBin/make.exe" "-DCMAKE_C_COMPILER=$ToolchainBin/gcc.exe" "-DCMAKE_CXX_COMPILER=$ToolchainBin/g++.exe" "-DLLAMA_SOURCE=$source" "-DLLAMA_VERSION=$Version" "-DJAVA_HOME=$Jdk" -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE) { throw 'Native configure failed' }
& $CMake --build (Join-Path $work 'compile') --target smartnpc_llama -j $Jobs
if ($LASTEXITCODE) { throw 'Native build failed' }
$ErrorActionPreference = 'Stop'
$destination = Join-Path $OutputDirectory 'META-INF/natives/smartnpc/windows-x64'
New-Item -ItemType Directory -Force $destination | Out-Null
Copy-Item -LiteralPath (Join-Path $work 'compile/smartnpc_llama.dll') -Destination $destination -Force
Write-Output "Generated embedded llama.cpp $Version runtime: $destination/smartnpc_llama.dll"
