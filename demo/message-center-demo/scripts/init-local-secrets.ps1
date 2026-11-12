$ErrorActionPreference = "Stop"

$SecretsDir = Join-Path (Split-Path -Parent $PSScriptRoot) "secrets"
[System.IO.Directory]::CreateDirectory($SecretsDir) | Out-Null

function New-RandomSecret {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet("Base64", "Hex")][string]$Format,
        [Parameter(Mandatory = $true)][int]$ByteCount
    )

    $Path = Join-Path $SecretsDir $Name
    if (Test-Path -LiteralPath $Path) {
        Write-Host "exists: $Name"
        return
    }

    $Bytes = [byte[]]::new($ByteCount)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($Bytes)
    if ($Format -eq "Hex") {
        $Value = [Convert]::ToHexString($Bytes).ToLowerInvariant()
    } else {
        $Value = [Convert]::ToBase64String($Bytes)
    }

    $Options = [System.IO.FileStreamOptions]::new()
    $Options.Mode = [System.IO.FileMode]::CreateNew
    $Options.Access = [System.IO.FileAccess]::Write
    $Options.Share = [System.IO.FileShare]::None
    if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [System.Runtime.InteropServices.OSPlatform]::Windows)) {
        $Options.UnixCreateMode = [System.IO.UnixFileMode]::UserRead -bor [System.IO.UnixFileMode]::UserWrite
    }

    $Stream = $null
    $Writer = $null
    try {
        $Stream = [System.IO.FileStream]::new($Path, $Options)
    } catch [System.IO.IOException] {
        [Array]::Clear($Bytes, 0, $Bytes.Length)
        $Value = $null
        if ([System.IO.File]::Exists($Path)) {
            Write-Host "exists: $Name"
            return
        }
        throw
    }
    try {
        $Writer = [System.IO.StreamWriter]::new($Stream, [System.Text.UTF8Encoding]::new($false))
        $Writer.Write($Value)
        $Writer.Flush()
    } finally {
        if ($null -ne $Writer) { $Writer.Dispose() }
        elseif ($null -ne $Stream) { $Stream.Dispose() }
        [Array]::Clear($Bytes, 0, $Bytes.Length)
        $Value = $null
    }
    Write-Host "created: $Name"
}

New-RandomSecret -Name "postgres_password" -Format "Base64" -ByteCount 32
New-RandomSecret -Name "minio_access_key" -Format "Hex" -ByteCount 20
New-RandomSecret -Name "minio_secret_key" -Format "Base64" -ByteCount 40
New-RandomSecret -Name "credential_master_key" -Format "Base64" -ByteCount 32
New-RandomSecret -Name "bootstrap_admin_password" -Format "Base64" -ByteCount 32
