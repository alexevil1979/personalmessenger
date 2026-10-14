$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root ".env"
if (Test-Path $target) {
    Write-Error ".env already exists. Refusing to overwrite it."
}

function New-Secret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return ([Convert]::ToBase64String($buffer)).TrimEnd("=").Replace("+", "A").Replace("/", "b")
}

$content = @"
POSTGRES_DB=messenger
POSTGRES_USER=messenger
POSTGRES_PASSWORD=$(New-Secret 24)
REDIS_PASSWORD=$(New-Secret 24)
MINIO_ROOT_USER=pm-storage
MINIO_ROOT_PASSWORD=$(New-Secret 24)
TURN_SECRET=$(New-Secret 32)
TURN_REALM=pm.1tlt.ru
PUBLIC_HOST=pm.1tlt.ru
ACME_EMAIL=admin@example.com
CORS_ORIGINS=https://pm.1tlt.ru,http://localhost
"@
Set-Content -Path $target -Value $content -Encoding utf8
Write-Host "Wrote $target. The generated secrets stay on this machine."
