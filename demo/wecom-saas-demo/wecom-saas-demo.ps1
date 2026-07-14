param(
    [ValidateSet("web", "test", "compile")]
    [string]$Command = "web"
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ($Command -eq "test") {
    mvn -q test-compile exec:java "-Dexec.mainClass=com.crmforlogistics.wecomsaas.WecomSaasDemoTest" "-Dexec.classpathScope=test"
    exit $LASTEXITCODE
}

if ($Command -eq "compile") {
    mvn -q -DskipTests compile
    exit $LASTEXITCODE
}

mvn -q exec:java "-Dexec.args=$Command"
exit $LASTEXITCODE
