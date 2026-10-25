[CmdletBinding()]
param(
    [ValidateSet('windows-x86_64', 'linux-x86_64', 'macos-x86_64', 'macos-aarch64')]
    [string]$Platform,
    [ValidateSet('Release', 'RelWithDebInfo', 'Debug')]
    [string]$Configuration = 'Release',
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $scriptDirectory '..\..'))
$nativeSource = Join-Path $projectDirectory 'src\main\native'

$architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
$runtimeInformation = [System.Runtime.InteropServices.RuntimeInformation]
$osPlatform = [System.Runtime.InteropServices.OSPlatform]
$detectedPlatform = $null
if ($runtimeInformation::IsOSPlatform($osPlatform::Windows) -and $architecture -eq 'X64') {
    $detectedPlatform = 'windows-x86_64'
} elseif ($runtimeInformation::IsOSPlatform($osPlatform::Linux) -and $architecture -eq 'X64') {
    $detectedPlatform = 'linux-x86_64'
} elseif ($runtimeInformation::IsOSPlatform($osPlatform::OSX) -and $architecture -eq 'X64') {
    $detectedPlatform = 'macos-x86_64'
} elseif ($runtimeInformation::IsOSPlatform($osPlatform::OSX) -and $architecture -eq 'Arm64') {
    $detectedPlatform = 'macos-aarch64'
} else {
    throw "Unsupported host platform: $($runtimeInformation::OSDescription) / $architecture"
}

if (-not $Platform) {
    $Platform = $detectedPlatform
} elseif ($Platform -ne $detectedPlatform) {
    throw "Requested $Platform on $($runtimeInformation::OSDescription) / $architecture ($detectedPlatform). Use a native runner for each resource platform."
}

$buildDirectory = Join-Path $projectDirectory "build\native\$Platform"
$resourceRoot = Join-Path $projectDirectory "build\generated\native-resources\natives\$Platform"

$cmakeArguments = @(
    '-S', $nativeSource,
    '-B', $buildDirectory,
    "-DCMAKE_BUILD_TYPE=$Configuration",
    "-DCMAKE_INSTALL_PREFIX=$resourceRoot",
    "-DBUILD_TESTING=$($RunTests.IsPresent.ToString().ToUpperInvariant())"
)
if ($env:SEEDATLAS_ENGINE_DIR) {
    $cmakeArguments += "-DSEEDATLAS_ENGINE_DIR=$($env:SEEDATLAS_ENGINE_DIR)"
}

cmake @cmakeArguments
if ($LASTEXITCODE -ne 0) { throw 'CMake configure failed' }

cmake --build $buildDirectory --config $Configuration --parallel
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }

if ($RunTests) {
    ctest --test-dir $buildDirectory -C $Configuration --output-on-failure
    if ($LASTEXITCODE -ne 0) { throw 'Native tests failed' }
}

cmake --install $buildDirectory --config $Configuration
if ($LASTEXITCODE -ne 0) { throw 'Native install failed' }

Write-Host "Native resource generated at $resourceRoot"
