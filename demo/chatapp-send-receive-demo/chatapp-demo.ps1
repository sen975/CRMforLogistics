param(
    [ValidateSet("web", "send", "sync", "sync-templates", "templates", "phones", "set-phone-webhook", "set-account-webhook", "mock-inbound")]
    [string]$Command = "web"
)

mvn -q exec:java "-Dexec.args=$Command"
