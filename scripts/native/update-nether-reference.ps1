param(
    # The exact 26.3 Minecraft common jar and its runtime dependency jars, separated by semicolons.
    [Parameter(Mandatory = $true)][string] $MinecraftClasspath
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = Join-Path $root 'build/native-reference'
New-Item -ItemType Directory -Force $build | Out-Null
$source = Join-Path $root 'src/main/native/tests/NetherVanillaReference.java'
$fixture = Join-Path $root 'src/main/native/tests/nether_vanilla_26_3.h'
# Java argument files require forward slashes inside quoted Windows paths.
@('-cp', ('"' + $MinecraftClasspath.Replace('\', '/') + '"'),
    '-d', ('"' + $build.Replace('\', '/') + '"'),
    ('"' + $source.Replace('\', '/') + '"')) | Set-Content (Join-Path $build 'javac.args')
& javac ('@' + (Join-Path $build 'javac.args'))
if ($LASTEXITCODE -ne 0) { throw 'Vanilla reference compilation failed' }
@('-cp', ('"' + ($build + ';' + $MinecraftClasspath).Replace('\', '/') + '"'),
    'NetherVanillaReference', ('"' + $fixture.Replace('\', '/') + '"')) |
    Set-Content (Join-Path $build 'java.args')
Push-Location $build
try {
    & java ('@' + (Join-Path $build 'java.args'))
    if ($LASTEXITCODE -ne 0) { throw 'Vanilla reference generation failed' }
} finally { Pop-Location }
