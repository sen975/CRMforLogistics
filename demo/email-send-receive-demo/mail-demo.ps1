param(
    [ValidateSet("verify", "send", "folders", "receive", "watch", "web", "watch-web", "help")]
    [string]$Command = "help",
    [string]$To,
    [string]$Subject,
    [string]$Body
)

$ErrorActionPreference = "Stop"

if ($Command -eq "help") {
    Write-Host "Usage:"
    Write-Host "  .\mail-demo.ps1 verify"
    Write-Host "  .\mail-demo.ps1 send"
    Write-Host "  .\mail-demo.ps1 send -To receiver@example.com -Subject 'CRM logistics mail test'"
    Write-Host "  .\mail-demo.ps1 folders"
    Write-Host "  .\mail-demo.ps1 receive"
    Write-Host "  .\mail-demo.ps1 watch"
    Write-Host "  .\mail-demo.ps1 web"
    Write-Host "  .\mail-demo.ps1 watch-web"
    exit 0
}

$mavenArgs = @("-q", "compile", "exec:java", "-Dexec.args=$Command")

if ($To) {
    $mavenArgs += "-Dmail.to=$To"
}
if ($Subject) {
    $mavenArgs += "-Dmail.subject=$Subject"
}
if ($Body) {
    $mavenArgs += "-Dmail.body=$Body"
}

& mvn @mavenArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
