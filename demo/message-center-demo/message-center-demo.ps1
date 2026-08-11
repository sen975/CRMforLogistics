param(
    [ValidateSet("web", "contacts", "receive", "sync", "sync-templates", "wecom-access-token", "wecom-debug-access-token", "test", "compile")]
    [string]$Command = "web",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments = @()
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ($Command -eq "test") {
    mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest" "-Dexec.classpathScope=test"
    exit $LASTEXITCODE
}

if ($Command -eq "compile") {
    mvn -q -DskipTests compile
    exit $LASTEXITCODE
}

$execArgs = @($Command) + $Arguments
mvn -q exec:java "-Dexec.args=$($execArgs -join ' ')"
exit $LASTEXITCODE
