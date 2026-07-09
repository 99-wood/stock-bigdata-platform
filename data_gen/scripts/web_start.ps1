$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Join-Path $scriptDir "..")

Write-Host "Checking image..."
$check = docker image inspect stock_collector_web:latest 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Building image..."
    docker build -f Dockerfile.web -t stock_collector_web:latest .
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Host "Starting Web UI..."
docker run -d --rm --name stock_collector_web `
    -p 5050:5050 `
    -v //var/run/docker.sock:/var/run/docker.sock `
    -v "$(Get-Location)\jsonl:/app/jsonl" `
    -e "JSONL_HOST_DIR=$(Get-Location)\jsonl" `
    -e REDIS_PASSWORD=1 `
    stock_collector_web:latest

Write-Host ""
Write-Host "Web UI: http://localhost:5050"
Write-Host "Stop: docker stop stock_collector_web"
Read-Host "Press Enter to exit"