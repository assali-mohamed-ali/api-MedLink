<#
.SYNOPSIS
    Start and verify the api-MedLink dev stack on Windows (Keycloak + Postgres + Spring Boot BFF).
    macOS and Linux: use scripts/setup-dev.sh, which does the same steps with the same options.

.DESCRIPTION
    Works on the clone this script lives in. The only things to install are Git and Docker Desktop.

    1. Checks prerequisites: Docker Desktop (running), curl.exe, a JDK 17+ (found automatically).
       Without a JDK 17+ it switches to -FullStack, where Docker builds and runs the API.
    2. Checks the repository layout.
    3. Creates .env from .env.example with generated secrets (only if .env is missing).
    4. Starts the containers and waits until Keycloak serves the "medlink" realm. The realm is
       re-imported automatically when keycloak/realm-medlink.json changed since the last run.
       Before any wipe, the current realm is saved to .dev\realm-backups\ (no users, no secrets).
    5. Verifies the imported realm through the Keycloak admin API (roles, groups, client, secret, users).
    6. Runs ./mvnw verify (unless -SkipTests).
    7. Starts the API and runs smoke tests, including a real login as practitioner.dev.

    Run from PowerShell (5.1 or 7):
        powershell -ExecutionPolicy Bypass -File .\scripts\setup-dev.ps1
    Re-running is safe: it restarts the app and re-checks everything.

.PARAMETER SkipTests
    Skip ./mvnw verify (faster).
.PARAMETER FullStack
    Run everything in containers with docker-compose.yml (app image + Prometheus) instead of
    compose.yaml + "mvnw spring-boot:run".
.PARAMETER Reset
    Delete the Docker volumes first (wipes Keycloak and app data, forces a fresh realm import).
.PARAMETER KeepData
    Never delete the Docker volumes, even when the realm file changed (the realm may then be stale).
.PARAMETER Stop
    Stop the app and the containers, then exit.
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$FullStack,
    [switch]$Reset,
    [switch]$KeepData,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $env:TEMP 'medlink-setup'
$PidFile = Join-Path $LogDir 'app.pid'
# Which realm file and compose mode the Keycloak volume was created from (git-ignored).
$RealmStamp = Join-Path $Repo '.dev\realm.stamp'
# Realm exports taken automatically before the dev volumes are deleted (git-ignored).
$BackupDir = Join-Path $Repo '.dev\realm-backups'
$KeycloakUrl = 'http://localhost:8180'
$AppUrl = 'http://localhost:8080'
$DevUsersPassword = 'MedLink-Dev-2026!'   # matches compose.yaml and the docs
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$script:Failures = 0
$script:Warnings = 0

function Write-Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "  [OK]   $Text" -ForegroundColor Green }
function Write-Warn([string]$Text) { $script:Warnings++; Write-Host "  [WARN] $Text" -ForegroundColor Yellow }
function Write-Bad([string]$Text) { $script:Failures++; Write-Host "  [FAIL] $Text" -ForegroundColor Red }
function Stop-Setup([string]$Text) { Write-Host "`n[ABORT] $Text" -ForegroundColor Red; exit 1 }

# Runs a native command and throws on a non-zero exit code. stderr is not redirected on purpose:
# Windows PowerShell 5.1 turns redirected stderr (git progress, docker pulls) into errors.
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "'$Exe $($Arguments -join ' ')' failed with exit code $LASTEXITCODE" }
}

# Exit code of a command run silently through cmd.exe (for checks only).
function Test-Quiet([string]$CommandLine) {
    cmd /c "$CommandLine >nul 2>&1"
    return ($LASTEXITCODE -eq 0)
}

function Get-HttpStatus([string]$Url) {
    $code = & curl.exe -s -o NUL -w '%{http_code}' --max-time 10 $Url
    return [int]$code
}

function Get-RedirectTarget([string]$Url) {
    return (& curl.exe -s -o NUL -w '%{redirect_url}' --max-time 10 $Url)
}

function Wait-Until([scriptblock]$Condition, [string]$What, [int]$TimeoutSec) {
    Write-Host "  waiting for $What (up to $TimeoutSec s)" -NoNewline
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) { Write-Host ' ready'; return $true }
        Write-Host '.' -NoNewline
        Start-Sleep -Seconds 5
    }
    Write-Host ' timed out'
    return $false
}

function Get-ListeningProcess([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { return $null }
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    if ($proc) { return "$($proc.ProcessName) (PID $($proc.Id))" }
    return "PID $($conn.OwningProcess)"
}

function Stop-App {
    if (Test-Path $PidFile) {
        $oldPid = Get-Content $PidFile
        if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
            cmd /c "taskkill /PID $oldPid /T /F >nul 2>&1"
            Write-Ok "stopped the previous app run (PID $oldPid)"
        }
        Remove-Item $PidFile -Force
    }
}

function Stop-Containers([switch]$Volumes) {
    Push-Location $Repo
    try {
        foreach ($file in 'compose.yaml', 'docker-compose.yml') {
            $downArgs = @('compose', '-f', $file, 'down')
            if ($Volumes) { $downArgs += '-v' }
            # docker-compose.yml needs its required variables even for "down": .env provides them.
            cmd /c "docker $($downArgs -join ' ') >nul 2>&1"
        }
    } finally { Pop-Location }
}

function Read-DotEnv([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content $Path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
    }
    return $values
}

function New-Secret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
}

# Major version of a JDK folder, read from its "release" file (0 when the folder is not a JDK).
function Get-JdkMajor([string]$JdkHome) {
    if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\javac.exe'))) { return 0 }
    $release = Join-Path $JdkHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    foreach ($line in Get-Content $release) {
        if ($line -match '^JAVA_VERSION="(\d+)') { return [int]$Matches[1] }
    }
    return 0
}

# A JDK 17+ on this machine: JAVA_HOME when it is recent enough, else the usual install folders
# (17 first, then the oldest newer one). Returns $null when there is none.
function Find-Jdk {
    if ((Get-JdkMajor $env:JAVA_HOME) -ge 17) { return $env:JAVA_HOME }
    $candidates = @()
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += Split-Path -Parent (Split-Path -Parent $onPath.Source) }
    $folders = @(
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\Zulu", "$env:ProgramFiles\BellSoft",
        "$env:USERPROFILE\.jdks", "$env:USERPROFILE\scoop\apps\openjdk17", "$env:USERPROFILE\scoop\apps\temurin17-jdk"
    )
    foreach ($folder in $folders) {
        if (Test-Path $folder) { $candidates += (Get-ChildItem $folder -Directory -ErrorAction SilentlyContinue).FullName }
    }
    $usable = $candidates | Where-Object { $_ } | Select-Object -Unique |
        ForEach-Object { [pscustomobject]@{ Home = $_; Major = Get-JdkMajor $_ } } |
        Where-Object { $_.Major -ge 17 } | Sort-Object Major
    if (-not $usable) { return $null }
    return @($usable)[0].Home
}

# Before the dev volumes are deleted: save the realm as it is in Keycloak (flows, roles, groups,
# clients; no users, secrets masked), so console work that was never exported is not lost.
# Best effort: a failure here is a warning, it never blocks the run.
function Backup-Realm([string]$PreviousComposeFile) {
    Push-Location $Repo
    try {
        $nameLine = (docker compose -f compose.yaml config 2>$null) | Where-Object { $_ -match '^name: (.+)$' } | Select-Object -First 1
        if ($nameLine -notmatch '^name: (.+)$') { return }
        if (-not (Test-Quiet "docker volume inspect $($Matches[1].Trim())_keycloak-db-data")) { return }   # nothing to save yet

        $realmUrl = "$KeycloakUrl/realms/medlink"
        if ((Get-HttpStatus $realmUrl) -ne 200) {
            cmd /c "docker compose -f $PreviousComposeFile up -d keycloak >nul 2>&1"
            if (-not (Wait-Until { (Get-HttpStatus $realmUrl) -eq 200 } 'Keycloak, to save the realm before the wipe' 180)) {
                Write-Warn 'the realm could not be saved before the wipe: Keycloak did not start'
                return
            }
        }

        $user = 'admin'; $pass = 'admin'                                # fixed dev values in compose.yaml
        if ($PreviousComposeFile -eq 'docker-compose.yml' -and (Test-Path '.env')) {
            $values = Read-DotEnv '.env'
            if ($values['KEYCLOAK_ADMIN']) { $user = $values['KEYCLOAK_ADMIN'] }
            $pass = $values['KEYCLOAK_ADMIN_PASSWORD']
        }
        $token = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = $user; password = $pass }

        New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
        $file = Join-Path $BackupDir ("realm-backup-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        & curl.exe -s -f --max-time 60 -X POST -H "Authorization: Bearer $($token.access_token)" -o $file `
            "$KeycloakUrl/admin/realms/medlink/partial-export?exportClients=true&exportGroupsAndRoles=true"
        if ($LASTEXITCODE -eq 0 -and (Test-Path $file) -and (Select-String -Path $file -Pattern '"realm"' -Quiet)) {
            Write-Ok "realm saved to $file before the wipe"
        } else {
            Remove-Item $file -Force -ErrorAction SilentlyContinue
            Write-Warn 'the realm could not be saved before the wipe: the export was refused'
        }
        # keep the 10 most recent backups
        Get-ChildItem $BackupDir -Filter 'realm-backup-*.json' | Sort-Object Name -Descending | Select-Object -Skip 10 | Remove-Item -Force
    } catch {
        Write-Warn "the realm could not be saved before the wipe: $($_.Exception.Message)"
    } finally { Pop-Location }
}

# The realm file is only imported into an empty Keycloak, so the volume is tied to the file it came from.
function Get-RealmStamp([string]$ComposeFile) {
    return "$ComposeFile $((Get-FileHash (Join-Path $Repo 'keycloak\realm-medlink.json') -Algorithm SHA256).Hash)"
}

# ---------------------------------------------------------------------------------------------
if ($Stop) {
    Write-Step 'Stopping api-MedLink'
    Stop-App
    if (Test-Path $Repo) { Stop-Containers; Write-Ok 'containers stopped (volumes kept)' }
    exit 0
}

# ---------------------------------------------------------------------------------------------
Write-Step '1/7 Checking prerequisites'

foreach ($tool in 'docker', 'curl.exe') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Stop-Setup "$tool is not installed or not on PATH." }
}
Write-Ok 'docker and curl.exe found'

if (-not (Test-Quiet 'docker info')) { Stop-Setup 'Docker is installed but not running. Start Docker Desktop, wait until it says "Engine running", then re-run.' }
if (-not (Test-Quiet 'docker compose version')) { Stop-Setup 'docker compose v2 is missing. Update Docker Desktop.' }
Write-Ok 'Docker Desktop is running (compose v2 available)'

if (-not $FullStack) {
    $jdk = Find-Jdk
    if ($jdk) {
        # Only for this run and the processes it starts: nothing is changed on the machine.
        $env:JAVA_HOME = $jdk
        $env:Path = "$jdk\bin;$env:Path"
        Write-Ok "JDK $(Get-JdkMajor $jdk) at $jdk"
    } else {
        $FullStack = $true
        Write-Host '  [INFO] no JDK 17+ found: switching to -FullStack, Docker builds and runs the API' -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------------------------
Write-Step '2/7 Checking the repository'

Set-Location $Repo

foreach ($required in 'compose.yaml', 'docker-compose.yml', 'keycloak\realm-medlink.json', 'mvnw.cmd', '.env.example') {
    if (-not (Test-Path $required)) { Stop-Setup "$required is missing in $Repo. Pull the latest master." }
}
Write-Ok "repository layout looks right ($Repo)"

# ---------------------------------------------------------------------------------------------
Write-Step '3/7 Preparing .env'

if (Test-Path '.env') {
    Write-Ok '.env already exists, keeping it'
} else {
    $content = Get-Content '.env.example' -Raw
    $content = $content -replace "`r`n", "`n"
    $generated = @{
        POSTGRES_PASSWORD       = New-Secret
        KEYCLOAK_DB_PASSWORD    = New-Secret
        KEYCLOAK_ADMIN_PASSWORD = New-Secret
        KC_BFF_CLIENT_SECRET    = New-Secret
        KC_ADMIN_CLIENT_SECRET  = New-Secret
        KC_DEV_USERS_PASSWORD   = $DevUsersPassword
    }
    foreach ($key in $generated.Keys) {
        $content = [regex]::Replace($content, "(?m)^$key=.*$", "$key=$($generated[$key])")
    }
    # UTF-8 without BOM: a BOM would corrupt the first variable name for docker compose.
    [System.IO.File]::WriteAllText((Join-Path $Repo '.env'), $content, (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok '.env created with random secrets (git-ignored, never commit it)'
}
# Keys added to .env.example after this .env was created: appended, secrets generated.
$envValues = Read-DotEnv '.env'
$added = @()
foreach ($key in (Read-DotEnv '.env.example').Keys) {
    if ($envValues.ContainsKey($key)) { continue }
    $value = (Read-DotEnv '.env.example')[$key]
    if ($key -like '*SECRET*' -or $key -like '*PASSWORD*') { $value = New-Secret }
    [System.IO.File]::AppendAllText((Join-Path $Repo '.env'), "`n$key=$value", (New-Object System.Text.UTF8Encoding($false)))
    $added += $key
}
if ($added) { Write-Ok "added to .env: $($added -join ', ')" }
$envValues = Read-DotEnv '.env'
foreach ($key in 'KC_BFF_CLIENT_SECRET', 'KC_ADMIN_CLIENT_SECRET', 'KEYCLOAK_DB_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'KC_DEV_USERS_PASSWORD') {
    if (-not $envValues[$key] -or $envValues[$key] -like 'change-me*') {
        if ($FullStack) { Stop-Setup "$key in .env is empty or still 'change-me'. Fill it in, or delete .env to regenerate it." }
    }
}

# ---------------------------------------------------------------------------------------------
Write-Step '4/7 Starting containers'

$composeFile = if ($FullStack) { 'docker-compose.yml' } else { 'compose.yaml' }
$wantedStamp = Get-RealmStamp $composeFile
$knownStamp = if (Test-Path $RealmStamp) { (Get-Content $RealmStamp -Raw).Trim() } else { '' }
if (-not $Reset -and $knownStamp -ne $wantedStamp) {
    if ($KeepData) { Write-Warn 'the realm file or the compose mode changed since the volumes were created; kept because of -KeepData' }
    else { $Reset = $true; Write-Host '  [INFO] realm file or compose mode changed since the last run: re-importing the realm' -ForegroundColor Yellow }
}

if ($Reset) {
    $previousComposeFile = ($knownStamp -split ' ')[0]
    if (-not $previousComposeFile) { $previousComposeFile = $composeFile }
    Backup-Realm $previousComposeFile
}

Stop-App
Stop-Containers -Volumes:$Reset
if ($Reset) { Write-Ok 'volumes deleted: Keycloak will import the realm from scratch' }

$busy = @()
$ports = @(8080, 8180, 1025, 8025)   # API, Keycloak, Mailpit SMTP and inbox
if ($FullStack) {
    # Host side only: inside the compose network the API still reaches postgres:5432.
    $pgPort = 5432
    while ((Get-ListeningProcess $pgPort) -and $pgPort -lt 5442) { $pgPort++ }
    if ($pgPort -ne 5432) { Write-Host "  [INFO] port 5432 is taken (a local PostgreSQL?): the database is published on $pgPort instead" -ForegroundColor Yellow }
    $env:POSTGRES_HOST_PORT = "$pgPort"
    $ports += $pgPort, 9090
}
foreach ($port in $ports) {
    $owner = Get-ListeningProcess $port
    if ($owner) { $busy += "port $port is used by $owner" }
}
if ($busy) { Stop-Setup ("Free these ports first:`n  " + ($busy -join "`n  ")) }
Write-Ok "ports $($ports -join ', ') are free"

if ($FullStack) {
    $kcAdminUser = $envValues['KEYCLOAK_ADMIN']; if (-not $kcAdminUser) { $kcAdminUser = 'admin' }
    $kcAdminPass = $envValues['KEYCLOAK_ADMIN_PASSWORD']
    $expectedSecret = $envValues['KC_BFF_CLIENT_SECRET']
    $expectedAdminSecret = $envValues['KC_ADMIN_CLIENT_SECRET']
    $usersPassword = $envValues['KC_DEV_USERS_PASSWORD']
    Invoke-Native docker @('compose', '-f', $composeFile, 'up', '-d', '--build')
} else {
    $kcAdminUser = 'admin'; $kcAdminPass = 'admin'               # fixed dev values in compose.yaml
    $expectedSecret = 'dev-only-medlink-bff-secret'              # compose.yaml + application-dev.yml
    $expectedAdminSecret = 'dev-only-medlink-admin-secret'       # compose.yaml + application-dev.yml
    $usersPassword = $DevUsersPassword
    Invoke-Native docker @('compose', '-f', $composeFile, 'up', '-d')
}
Write-Ok "containers started from $composeFile"

$realmUp = Wait-Until { (Get-HttpStatus "$KeycloakUrl/realms/medlink/.well-known/openid-configuration") -eq 200 } 'Keycloak and the medlink realm' 420
if (-not $realmUp) {
    Write-Host '  last Keycloak log lines:' -ForegroundColor Yellow
    docker compose -f $composeFile logs --tail 60 keycloak
    Stop-Setup 'Keycloak did not serve the medlink realm. Check the log above (realm import errors show up there).'
}
Write-Ok 'Keycloak is up and serves realm medlink'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $RealmStamp) | Out-Null
Set-Content -Path $RealmStamp -Value $wantedStamp

# ---------------------------------------------------------------------------------------------
Write-Step '5/7 Verifying the imported realm (Keycloak admin API)'

try {
    $token = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = $kcAdminUser; password = $kcAdminPass }
    $auth = @{ Authorization = "Bearer $($token.access_token)" }
    $admin = "$KeycloakUrl/admin/realms/medlink"

    $wellKnown = Invoke-RestMethod "$KeycloakUrl/realms/medlink/.well-known/openid-configuration"
    if ($wellKnown.issuer -eq "$KeycloakUrl/realms/medlink") { Write-Ok "issuer = $($wellKnown.issuer)" }
    else { Write-Bad "issuer is $($wellKnown.issuer), expected $KeycloakUrl/realms/medlink (check KC_HOSTNAME)" }

    $roleNames = (Invoke-RestMethod "$admin/roles" -Headers $auth).name
    $missingRoles = @('ADMINISTRATIVE_STAFF', 'PRACTITIONER', 'PATIENT') | Where-Object { $roleNames -notcontains $_ }
    if ($missingRoles) { Write-Bad "missing realm roles: $($missingRoles -join ', ')" } else { Write-Ok 'roles ADMINISTRATIVE_STAFF, PRACTITIONER, PATIENT' }

    $groupNames = (Invoke-RestMethod "$admin/groups" -Headers $auth).name
    $missingGroups = @('administrative-staff', 'practitioners', 'patients') | Where-Object { $groupNames -notcontains $_ }
    if ($missingGroups) { Write-Bad "missing groups: $($missingGroups -join ', ')" } else { Write-Ok 'groups administrative-staff, practitioners, patients' }

    $client = @(Invoke-RestMethod "$admin/clients?clientId=medlink-bff" -Headers $auth)
    if ($client.Count -ne 1) {
        Write-Bad 'client medlink-bff not found'
    } else {
        Write-Ok 'client medlink-bff exists'
        $c = $client[0]
        if ($c.redirectUris -match '\$\{') { Write-Bad "redirect URIs contain an unresolved placeholder: $($c.redirectUris -join ', ')" }
        else { Write-Ok "redirect URI $($c.redirectUris -join ', ')" }
        if ($c.attributes.'pkce.code.challenge.method' -eq 'S256') { Write-Ok 'PKCE S256 enforced' } else { Write-Bad 'PKCE S256 not set on the client' }
        $secret = (Invoke-RestMethod "$admin/clients/$($c.id)/client-secret" -Headers $auth).value
        if ($secret -eq $expectedSecret) { Write-Ok 'client secret matches what the API uses' }
        elseif ($secret -match '\$\{') { Write-Bad 'client secret is an unresolved placeholder: KC_BFF_CLIENT_SECRET was not passed to Keycloak' }
        else { Write-Bad 'client secret differs from what the API uses (realm imported earlier with another secret? re-run with -Reset)' }
    }

    # Service account of the API: can manage users, nothing more (least rights).
    $adminClient = @(Invoke-RestMethod "$admin/clients?clientId=medlink-admin" -Headers $auth)
    if ($adminClient.Count -ne 1) {
        Write-Bad 'client medlink-admin not found'
    } else {
        $adminSecret = (Invoke-RestMethod "$admin/clients/$($adminClient[0].id)/client-secret" -Headers $auth).value
        if ($adminSecret -ne $expectedAdminSecret) {
            Write-Bad 'client medlink-admin: secret differs from what the API uses (KC_ADMIN_CLIENT_SECRET; re-run with -Reset)'
        } else {
            $saToken = Invoke-RestMethod -Method Post -Uri "$KeycloakUrl/realms/medlink/protocol/openid-connect/token" `
                -ContentType 'application/x-www-form-urlencoded' `
                -Body @{ grant_type = 'client_credentials'; client_id = 'medlink-admin'; client_secret = $adminSecret }
            $saAuth = "Authorization: Bearer $($saToken.access_token)"
            $listUsers = [int](& curl.exe -s -o NUL -w '%{http_code}' --max-time 10 -H $saAuth "$admin/users?max=1")
            # GET on the realm itself answers 200 with a short summary to any admin: ask for its clients instead.
            $realmSettings = [int](& curl.exe -s -o NUL -w '%{http_code}' --max-time 10 -H $saAuth "$admin/clients")
            if ($listUsers -eq 200 -and $realmSettings -eq 403) { Write-Ok 'service account medlink-admin: can list users, refused on realm settings (clients)' }
            else { Write-Bad "service account medlink-admin: list users $listUsers (expected 200), realm clients $realmSettings (expected 403)" }
        }
    }

    $userNames = (Invoke-RestMethod "$admin/users?briefRepresentation=true&max=50" -Headers $auth).username
    $missingUsers = @('staff.dev', 'practitioner.dev', 'patient.dev') | Where-Object { $userNames -notcontains $_ }
    if ($missingUsers) { Write-Warn "test users missing: $($missingUsers -join ', ') (deleted on purpose?)" } else { Write-Ok 'test users staff.dev, practitioner.dev, patient.dev' }
} catch {
    Write-Bad "Keycloak admin API check failed: $($_.Exception.Message)"
    if (-not $FullStack) { Write-Host '  (if Keycloak data comes from an older run with another admin password, re-run with -Reset)' }
}

# ---------------------------------------------------------------------------------------------
if ($FullStack) {
    Write-Step '6/7 Tests skipped in -FullStack mode (the image build compiles the code)'
} elseif ($SkipTests) {
    Write-Step '6/7 Tests skipped (-SkipTests)'
} else {
    Write-Step '6/7 Running ./mvnw verify (first run downloads Maven and dependencies; can take several minutes)'
    $testLog = Join-Path $LogDir 'mvn-verify.log'
    # ".\": cmd does not search the current folder when NoDefaultCurrentDirectoryInExePath is set.
    cmd /c ".\mvnw.cmd -B verify > `"$testLog`" 2>&1"
    if ($LASTEXITCODE -eq 0) {
        $summary = Select-String -Path $testLog -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' | Select-Object -Last 1
        Write-Ok "build and tests passed $(if ($summary) { "($($summary.Line.Trim() -replace '^\[\w+\]\s*',''))" })"
    } else {
        Get-Content $testLog -Tail 60
        Write-Bad "mvnw verify failed: full log in $testLog, test reports in $Repo\target\surefire-reports"
    }
}

# ---------------------------------------------------------------------------------------------
Write-Step '7/7 Starting the API and running smoke tests'

if (-not $FullStack) {
    $appLog = Join-Path $LogDir 'app.log'
    $proc = Start-Process -FilePath 'cmd.exe' -WorkingDirectory $Repo -WindowStyle Hidden -PassThru `
        -ArgumentList '/c', "set SPRING_PROFILES_ACTIVE=dev&& .\mvnw.cmd -B spring-boot:run > `"$appLog`" 2>&1"
    Set-Content -Path $PidFile -Value $proc.Id
    Write-Ok "API starting in the background (PID $($proc.Id), log $appLog)"
}

$appUp = Wait-Until { (Get-HttpStatus "$AppUrl/actuator/health") -eq 200 } 'the API health endpoint' 600
if (-not $appUp) {
    if ($FullStack) { docker compose -f $composeFile logs --tail 80 app } else { Get-Content (Join-Path $LogDir 'app.log') -Tail 80 }
    Stop-Setup 'The API did not become healthy. See the log above.'
}

$health = & curl.exe -s --max-time 10 "$AppUrl/actuator/health"
if ($health -match '"status":"UP"') { Write-Ok "health: $health" } else { Write-Bad "health is not UP: $health" }

$status = Get-HttpStatus "$AppUrl/api/users"
if ($status -eq 401) { Write-Ok '/api/users without a session returns 401' } else { Write-Bad "/api/users without a session returned $status, expected 401" }

$location = Get-RedirectTarget "$AppUrl/oauth2/authorization/keycloak"
if ($location -like "$KeycloakUrl/realms/medlink/protocol/openid-connect/auth*" -and $location -match 'code_challenge_method=S256') {
    Write-Ok 'login redirects to Keycloak with PKCE S256'
} else { Write-Bad "login redirect is wrong: '$location'" }

$swagger = Get-HttpStatus "$AppUrl/swagger-ui.html"
if ($swagger -in 200, 302) { Write-Ok 'Swagger UI reachable' } else { Write-Warn "Swagger UI returned $swagger" }

# End-to-end: log in as practitioner.dev through the Keycloak login form, then call the API with the session.
# curl.exe with a cookie jar, like setup-dev.sh: Invoke-WebRequest loses the session cookie on the redirects.
$cookies = Join-Path $LogDir 'cookies.txt'
Remove-Item $cookies -Force -ErrorAction SilentlyContinue
$loginPage = (& curl.exe -s -L --max-time 30 -c $cookies -b $cookies "$AppUrl/oauth2/authorization/keycloak") -join "`n"
if ($loginPage -match 'action="([^"]*login-actions/authenticate[^"]*)"') {
    $action = [System.Net.WebUtility]::HtmlDecode($Matches[1])
    # After login Spring redirects to "/", which has no page (404): expected, the session is what matters.
    & curl.exe -s -L -o NUL --max-time 30 -c $cookies -b $cookies `
        --data-urlencode 'username=practitioner.dev' --data-urlencode "password=$usersPassword" -d 'credentialId=' $action
    $apiStatus = [int](& curl.exe -s -o NUL -w '%{http_code}' --max-time 10 -b $cookies "$AppUrl/api/users")
    if ($apiStatus -eq 200) { Write-Ok 'end-to-end login as practitioner.dev works: /api/users returns 200 with the session' }
    else { Write-Bad "after login /api/users returned $apiStatus" }
} else {
    Write-Warn "automated login check did not complete (Keycloak login form not found). Test it in a browser: $AppUrl/oauth2/authorization/keycloak"
}
Remove-Item $cookies -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------------------------
Write-Host "`n------------------------------------------------------------" -ForegroundColor Cyan
if ($script:Failures -eq 0) {
    Write-Host "api-MedLink is up. $($script:Warnings) warning(s)." -ForegroundColor Green
} else {
    Write-Host "api-MedLink is up with $($script:Failures) failed check(s) and $($script:Warnings) warning(s). See [FAIL] lines above." -ForegroundColor Red
}
Write-Host @"

  API             $AppUrl
  Login           $AppUrl/oauth2/authorization/keycloak
  Swagger UI      $AppUrl/swagger-ui.html
  Mail inbox      http://localhost:8025   (every mail the API sends, Mailpit)
  Keycloak admin  $KeycloakUrl   (user: $kcAdminUser, password: $(if ($FullStack) { 'KEYCLOAK_ADMIN_PASSWORD in .env' } else { 'admin' }))
  Test users      staff.dev / practitioner.dev / patient.dev   (password: $(if ($FullStack) { 'KC_DEV_USERS_PASSWORD in .env' } else { $DevUsersPassword }))
  Logs            $LogDir

  Stop everything:  powershell -ExecutionPolicy Bypass -File "$Repo\scripts\setup-dev.ps1" -Stop
  Fresh realm:      re-run with -Reset   (deletes Keycloak and app data; automatic when the realm file changes)
"@
if ($script:Failures -gt 0) { exit 1 }
