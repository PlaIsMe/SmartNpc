param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('fabric', 'forge')]
    [string] $Loader,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$clientGradleHome = Join-Path $projectRoot '.gradle/client-home'
$sharedGradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
New-Item -ItemType Directory -Path (Join-Path $clientGradleHome 'caches') -Force | Out-Null

# Share downloaded dependencies, but keep Loom mappings separate from IDE imports.
foreach ($relative in @('wrapper', 'caches/modules-2', 'caches/fabric-loom/assets')) {
    $source = Join-Path $sharedGradleHome $relative
    $target = Join-Path $clientGradleHome $relative
    if ((Test-Path -LiteralPath $source) -and !(Test-Path -LiteralPath $target)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        New-Item -ItemType Junction -Path $target -Target $source | Out-Null
    }
}

$task = if ($Loader -eq 'fabric') { 'runClientFabric' } else { 'runClientForge' }
$gradleArguments = @('-g', $clientGradleHome, $task, '--console=plain')
if ($DryRun) { $gradleArguments += '--dry-run' }
Push-Location $projectRoot
try {
    # Windows PowerShell can wrap ordinary javac stderr warnings as errors.
    # Let Gradle's exit code determine whether the native command failed.
    $ErrorActionPreference = 'Continue'
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
    $result = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
}
exit $result
