$ProjectCheckCommand = "google-android-security"
$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$ProjectCheckEntryPoint = "C:\Dev\Android-check\tools\InvokeProjectCheck.ps1"
if (-not (Test-Path -LiteralPath $ProjectCheckEntryPoint -PathType Leaf)) {
    Write-Error "ERROR/2: Android-check entry point is missing: $ProjectCheckEntryPoint"
    exit 2
}
$global:LASTEXITCODE = 2
& $ProjectCheckEntryPoint -ProjectCheckCommand $ProjectCheckCommand -Root $ProjectRoot -ProjectId "startex" @args
exit $LASTEXITCODE
