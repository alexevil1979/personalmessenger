param(
    [string]$Url = "http://127.0.0.1:8080/api/v1/health"
)
$ErrorActionPreference = "Stop"
$response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
Write-Host "HTTP $($response.StatusCode)"
Write-Host $response.Content
if ($response.StatusCode -ne 200) { exit 1 }
