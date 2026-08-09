$ProjectCheckCommand = "pmd-check"
$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
& "C:\Dev\Android-check\tools\InvokeProjectCheck.ps1" -ProjectCheckCommand $ProjectCheckCommand -Root $ProjectRoot -ProjectId "startex" @args
exit $LASTEXITCODE
