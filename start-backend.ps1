param([switch]$StartRabbitMq)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not $env:RABBITMQ_USERNAME -or -not $env:RABBITMQ_PASSWORD) {
        throw 'Set RABBITMQ_USERNAME and RABBITMQ_PASSWORD in this terminal before starting.'
    }
    if (Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue) {
        throw 'Port 8081 is already in use. Stop the existing backend before starting another instance.'
    }
    if ($StartRabbitMq) {
        docker compose --profile local-rabbitmq up -d --wait
    } else {
        docker compose up -d --wait
    }
    if ($LASTEXITCODE -ne 0) { throw 'Redis/RabbitMQ startup failed. Check Docker Desktop and port conflicts.' }
    mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error. Check the log above.' }
} finally {
    Pop-Location
}
