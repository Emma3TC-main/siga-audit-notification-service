param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$infra = [IO.Path]::GetFullPath((Join-Path $root '../siga-infrastructure'))
$composeFile = Join-Path $infra 'compose/compose.local.yml'
$envFile = Join-Path $infra 'compose/.env.local'
$queuePattern = '^siga\.identity\.audit$'

$localEnvironment = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $localEnvironment[$Matches[1]] = $Matches[2]
    }
}
$rabbitUser = $localEnvironment['LOCAL_RABBITMQ_USER']
$rabbitPassword = $localEnvironment['LOCAL_RABBITMQ_PASSWORD']
if (!$rabbitUser -or !$rabbitPassword) {
    throw 'RabbitMQ credentials are missing from the existing local Compose configuration.'
}
$rabbitAdmin = @(
    'exec',
    '-e', "RABBITMQADMIN_USERNAME=$rabbitUser",
    '-e', "RABBITMQADMIN_PASSWORD=$rabbitPassword",
    'siga_rabbitmq',
    'rabbitmqadmin'
)
docker compose --project-name siga-local --env-file $envFile -f $composeFile config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid existing siga-local Compose configuration.' }

if (!$Apply) {
    Write-Host 'Validated Identity Audit dead-letter plan (no RabbitMQ changes applied).'
    Write-Host 'Run with -Apply only during the controlled Slice D activation.'
    return
}

$rabbit = docker inspect siga_rabbitmq | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $rabbit[0].Config.Labels.'com.docker.compose.project' -ne 'siga-local') {
    throw 'RabbitMQ is not the expected siga-local container.'
}

docker @rabbitAdmin declare exchange --name siga.identity.audit.dlx --type direct --durable true --non-interactive
if ($LASTEXITCODE -ne 0) { throw 'Could not declare Identity Audit DLX.' }
docker @rabbitAdmin declare queue --name siga.identity.audit.dlq --durable true --non-interactive
if ($LASTEXITCODE -ne 0) { throw 'Could not declare Identity Audit DLQ.' }
docker @rabbitAdmin declare binding --source siga.identity.audit.dlx --destination-type queue --destination siga.identity.audit.dlq --routing-key siga.identity.audit.dead --non-interactive
if ($LASTEXITCODE -ne 0) { throw 'Could not bind Identity Audit DLQ.' }
$basicToken = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes("${rabbitUser}:${rabbitPassword}")
)
$policyBody = @{
    pattern = $queuePattern
    definition = @{
        'dead-letter-exchange' = 'siga.identity.audit.dlx'
        'dead-letter-routing-key' = 'siga.identity.audit.dead'
    }
    priority = 0
    'apply-to' = 'queues'
} | ConvertTo-Json -Compress
try {
    Invoke-RestMethod `
        -Method Put `
        -Uri 'http://127.0.0.1:15672/api/policies/%2F/siga-identity-audit-dead-letter' `
        -Headers @{ Authorization = "Basic $basicToken" } `
        -ContentType 'application/json' `
        -Body $policyBody | Out-Null
} catch {
    throw 'Could not apply Identity Audit queue policy.'
}

Write-Host 'Identity Audit DLX/DLQ/policy configured without redeclaring or purging the source queue.'
