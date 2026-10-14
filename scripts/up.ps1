$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "Docker is not available. Run backend tests with: cd backend; .\gradlew.bat test"
}
if (-not (Test-Path ".env")) {
    Write-Error ".env is missing. Run scripts/new-env.ps1 first. Do not reuse example passwords."
}
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
Write-Host "Waiting for health..."
$deadline = (Get-Date).AddMinutes(3)
do {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/health" -TimeoutSec 5
        $health | ConvertTo-Json -Depth 6
        if ($health.status -eq "UP") { exit 0 }
    } catch {
        Start-Sleep -Seconds 3
    }
} while ((Get-Date) -lt $deadline)
Write-Error "Backend health did not become UP within 3 minutes."
