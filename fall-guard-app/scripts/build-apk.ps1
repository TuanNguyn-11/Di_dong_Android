param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$TestOnDevice
)
$ErrorActionPreference = 'Stop'
$appRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) {
    $candidates = @(
        'C:\DDisk\Apps\AndroidStudio\AppAS\jbr',
        "$env:ProgramFiles\Android\Android Studio\jbr"
    )
    $JdkHome = $candidates | Where-Object { Test-Path (Join-Path $_ 'bin/java.exe') } | Select-Object -First 1
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/java.exe'))) {
    throw 'Specify JDK 21 with -JdkHome or JAVA_HOME.'
}
$originalJavaHome = $env:JAVA_HOME
Push-Location $appRoot
try {
    $env:JAVA_HOME = $JdkHome
    # Windows PowerShell treats native stderr (including esbuild progress) as errors.
    $ErrorActionPreference = 'Continue'
    & npm.cmd run sync
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw 'Web build / Capacitor sync failed.' }
    Push-Location (Join-Path $appRoot 'android')
    try {
        $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', '--console=plain')
        if ($TestOnDevice) { $tasks += ':app:connectedDebugAndroidTest' }
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat @tasks
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Android build/tests failed.' }
    } finally { Pop-Location }
    $artifactDir = Join-Path $appRoot 'artifacts'
    New-Item -ItemType Directory -Force $artifactDir | Out-Null
    $apk = Join-Path $artifactDir 'fall-guard-ai-debug.apk'
    Copy-Item (Join-Path $appRoot 'android/app/build/outputs/apk/debug/app-debug.apk') $apk -Force
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$apk.sha256" -Value "$hash  fall-guard-ai-debug.apk" -Encoding ascii
    Write-Host "APK: $apk"
    Write-Host "SHA256: $hash"
} finally {
    Pop-Location
    $env:JAVA_HOME = $originalJavaHome
}
