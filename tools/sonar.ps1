#Requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$PlanOnly,

    [switch]$AllowExternalUpload,

    [ValidateRange(1, 86400)]
    [int]$GradleTimeoutSeconds = 3600
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [Console]::OutputEncoding

function Get-SonarProjectProperties {
    param([string]$Path)

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }

        $separator = $trimmed.IndexOf("=")
        if ($separator -gt 0) {
            $properties[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
        }
    }

    return $properties
}

function Test-SonarTokenConfigured {
    param([string]$RepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($env:SONAR_TOKEN)) {
        return $true
    }

    foreach ($path in @(
        (Join-Path $env:USERPROFILE ".gradle\gradle.properties"),
        (Join-Path $RepoRoot "gradle.properties")
    )) {
        if (
            (Test-Path -LiteralPath $path -PathType Leaf) -and
            (Select-String -LiteralPath $path -Pattern "^\s*systemProp\.sonar\.token\s*=" -Quiet)
        ) {
            return $true
        }
    }

    return $false
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$propertiesPath = Join-Path $repoRoot "sonar-project.properties"
if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
    throw "sonar-project.properties ei loytynyt: $propertiesPath"
}

$sonarProperties = Get-SonarProjectProperties -Path $propertiesPath
$projectKey = [string]$sonarProperties["sonar.projectKey"]
$hostUrl = [string]$sonarProperties["sonar.host.url"]
if ([string]::IsNullOrWhiteSpace($projectKey)) {
    throw "sonar.projectKey puuttuu sonar-project.properties-tiedostosta."
}
if ([string]::IsNullOrWhiteSpace($hostUrl)) {
    $hostUrl = "https://sonarcloud.io"
}

if ($PlanOnly) {
    Write-Output @(
        "sonar"
        "  - Gradle debug build, JVM unit-test coverage and SonarCloud upload: reports/sonar.txt"
        "  - coverage includes JVM unit tests only; instrumented and device behavior stay outside the report"
        "  - requires SONAR_TOKEN or systemProp.sonar.token"
        "  - actual external upload requires -AllowExternalUpload"
        "  - project: $projectKey"
        "  - host: $hostUrl"
    )
    exit 0
}

$reportsDirectory = Join-Path $repoRoot "reports"
$reportPath = Join-Path $reportsDirectory "sonar.txt"
New-Item -ItemType Directory -Force -Path $reportsDirectory | Out-Null
Set-Content -LiteralPath $reportPath -Encoding utf8 -Value @(
    "sonar"
    "Root: $repoRoot"
    "Project: $projectKey"
    "Command: .\gradlew.bat sonar --console=plain --no-daemon"
    "Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    ""
)

if (-not $AllowExternalUpload) {
    Add-Content -LiteralPath $reportPath -Encoding utf8 -Value @(
        "ERROR: EXTERNAL_UPLOAD_APPROVAL_REQUIRED"
        "Sonar-analyysi voi lahettaa lahdekoodia ja analyysimetatietoa ulkoiseen palveluun."
        "Kayta -AllowExternalUpload-valitsinta vain, kun ulkoinen lahetys on sallittu."
    )
    Get-Content -LiteralPath $reportPath
    exit 2
}

if (-not (Test-SonarTokenConfigured -RepoRoot $repoRoot)) {
    Add-Content -LiteralPath $reportPath -Encoding utf8 -Value "ERROR: SONAR_TOKEN_MISSING"
    Get-Content -LiteralPath $reportPath
    exit 2
}

Push-Location -LiteralPath $repoRoot
try {
    $env:SONAR_HOST_URL = if ($env:SONAR_HOST_URL) { $env:SONAR_HOST_URL } else { $hostUrl }

    try {
        Import-Module "C:\Dev\Android-check\tools\CheckRuntime.psm1" -Force -ErrorAction Stop
        $result = Invoke-ManagedProcess `
            -Executable (Join-Path $repoRoot "gradlew.bat") `
            -Arguments @("sonar", "--console=plain", "--no-daemon") `
            -WorkingDirectory $repoRoot `
            -TimeoutSeconds $GradleTimeoutSeconds
    }
    catch {
        Add-Content -LiteralPath $reportPath -Encoding utf8 -Value "ERROR: SONAR_ANALYSIS_PROCESS_ERROR: $($_.Exception.Message)"
        exit 2
    }

    foreach ($text in @($result.StandardOutput, $result.StandardError)) {
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            Add-Content -LiteralPath $reportPath -Encoding utf8 -Value $text
            Write-Output $text
        }
    }

    if ($result.TimedOut) {
        Add-Content -LiteralPath $reportPath -Encoding utf8 -Value "ERROR: SONAR_ANALYSIS_TIMEOUT ($GradleTimeoutSeconds s)"
        exit 2
    }
    if ($result.ExitCode -ne 0) {
        Add-Content -LiteralPath $reportPath -Encoding utf8 -Value "ERROR: SONAR_ANALYSIS_FAILED (exit $($result.ExitCode))"
        exit 2
    }

    Add-Content -LiteralPath $reportPath -Encoding utf8 -Value @(
        "SONAR_ANALYSIS_UPLOAD_COMPLETED"
        "The JaCoCo import contains JVM unit-test coverage only; instrumented and device behavior remain separate evidence."
    )
    exit 0
}
finally {
    Pop-Location
}
