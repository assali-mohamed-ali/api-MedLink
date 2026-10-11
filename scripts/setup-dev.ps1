<#
.SYNOPSIS
    Clone, start and verify the api-MedLink dev stack on Windows (Keycloak + Postgres + Spring Boot BFF).

.DESCRIPTION
    1. Checks prerequisites: Git, Docker Desktop (running), Java 17+, curl.exe, free ports.
    2. Clones the repo into -TargetDir (or updates it if already cloned and clean).
    3. Creates .env from .env.example with generated secrets (only if .env is missing).
    4. Starts the containers and waits until Keycloak serves the "medlink" realm.
    5. Verifies the imported realm through the Keycloak admin API (roles, groups, client, secret, users).
    6. Runs ./mvnw verify (unless -SkipTests).
    7. Starts the API and runs smoke tests, including a real login as practitioner.dev.

    Run from PowerShell (5.1 or 7):
        powershell -ExecutionPolicy Bypass -File .\setup-dev.ps1
    Re-running is safe: it updates the clone, restarts the app and re-checks everything.

.PARAMETER TargetDir
    Folder that will contain the api-MedLink clone.
.PARAMETER Branch
    Branch to check out.
.PARAMETER SkipTests
    Skip ./mvnw verify (faster).
.PARAMETER FullStack
    Run everything in containers with docker-compose.yml (app image + Prometheus) instead of
    compose.yaml + "mvnw spring-boot:run".
.PARAMETER Reset
    Delete the Docker volumes first (wipes Keycloak and app data, forces a fresh realm import).
.PARAMETER Stop
    Stop the app and the containers, then exit.
#>
[CmdletBinding()]
param(
    [string]$TargetDir = 'C:\Users\ASUS\Documents\ESPRIM\MedLink\code',
    [string]$Branch = 'feat/keycloak-init',
    [switch]$SkipTests,
    [switch]$FullStack,
    [switch]$Reset,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
$RepoUrl = 'https://github.com/assali-mohamed-ali/api-MedLink.git'
$Repo = Join-Path $TargetDir 'api-MedLink'
$LogDir = Join-Path $env:TEMP 'medlink-setup'
$PidFile = Join-Path $LogDir 'app.pid'
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

# ---------------------------------------------------------------------------------------------
if ($Stop) {
    Write-Step 'Stopping api-MedLink'
    Stop-App
    if (Test-Path $Repo) { Stop-Containers; Write-Ok 'containers stopped (volumes kept)' }
    exit 0
}

# ---------------------------------------------------------------------------------------------
Write-Step '1/7 Checking prerequisites'

foreach ($tool in 'git', 'docker', 'curl.exe') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Stop-Setup "$tool is not installed or not on PATH." }
}
Write-Ok 'git, docker and curl.exe found'

if (-not (Test-Quiet 'docker info')) { Stop-Setup 'Docker is installed but not running. Start Docker Desktop, wait until it says "Engine running", then re-run.' }
if (-not (Test-Quiet 'docker compose version')) { Stop-Setup 'docker compose v2 is missing. Update Docker Desktop.' }
Write-Ok 'Docker Desktop is running (compose v2 available)'

if (-not $FullStack) {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Stop-Setup 'Java is not on PATH. Install a JDK 17+ (e.g. Temurin 17) and set JAVA_HOME.' }
    $javaVersion = (cmd /c 'java -version 2>&1') -join ' '
    if ($javaVersion -match 'version "(\d+)') {
        $major = [int]$Matches[1]
        if ($major -lt 17) { Stop-Setup "Java $major found, Java 17+ is required. Install Temurin 17 and update JAVA_HOME/PATH." }
        Write-Ok "Java $major"
    } else {
        Stop-Setup "Could not read the Java version: $javaVersion"
    }
}

# ---------------------------------------------------------------------------------------------
Write-Step "2/7 Getting the code ($Branch)"

if (Test-Path (Join-Path $Repo '.git')) {
    Push-Location $Repo
    try {
        $dirty = (git status --porcelain) -join "`n"
        Invoke-Native git @('fetch', 'origin', $Branch)
        if ($dirty) {
            Write-Warn "local changes found in $Repo; not switching or pulling. Commit or stash them to get the latest $Branch."
        } else {
            Invoke-Native git @('checkout', $Branch)
            Invoke-Native git @('pull', '--ff-only', 'origin', $Branch)
            Write-Ok "updated existing clone to the latest $Branch"
        }
    } finally { Pop-Location }
} else {
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    Invoke-Native git @('clone', '--branch', $Branch, $RepoUrl, $Repo)
    Write-Ok "cloned into $Repo"
}
Set-Location $Repo

foreach ($required in 'compose.yaml', 'docker-compose.yml', 'keycloak\realm-medlink.json', 'mvnw.cmd') {
    if (-not (Test-Path $required)) { Stop-Setup "$required is missing: is $Branch the Keycloak branch?" }
}
Write-Ok 'repository layout looks right'

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
        KC_DEV_USERS_PASSWORD   = $DevUsersPassword
    }
    foreach ($key in $generated.Keys) {
        $content = [regex]::Replace($content, "(?m)^$key=.*$", "$key=$($generated[$key])")
    }
    # UTF-8 without BOM: a BOM would corrupt the first variable name for docker compose.
    [System.IO.File]::WriteAllText((Join-Path $Repo '.env'), $content, (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok '.env created with random secrets (git-ignored, never commit it)'
}
$envValues = Read-DotEnv '.env'
foreach ($key in 'KC_BFF_CLIENT_SECRET', 'KEYCLOAK_DB_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'KC_DEV_USERS_PASSWORD') {
    if (-not $envValues[$key] -or $envValues[$key] -like 'change-me*') {
        if ($FullStack) { Stop-Setup "$key in .env is empty or still 'change-me'. Fill it in, or delete .env to regenerate it." }
    }
}

# ---------------------------------------------------------------------------------------------
Write-Step '4/7 Starting containers'

Stop-App
Stop-Containers -Volumes:$Reset
if ($Reset) { Write-Ok 'volumes deleted: Keycloak will import the realm from scratch' }

$busy = @()
$ports = @(8080, 8180)
if ($FullStack) { $ports += 5432, 9090 }
foreach ($port in $ports) {
    $owner = Get-ListeningProcess $port
    if ($owner) { $busy += "port $port is used by $owner" }
}
if ($busy) { Stop-Setup ("Free these ports first:`n  " + ($busy -join "`n  ")) }
Write-Ok "ports $($ports -join ', ') are free"

if ($FullStack) {
    $composeFile = 'docker-compose.yml'
    $kcAdminUser = $envValues['KEYCLOAK_ADMIN']; if (-not $kcAdminUser) { $kcAdminUser = 'admin' }
    $kcAdminPass = $envValues['KEYCLOAK_ADMIN_PASSWORD']
    $expectedSecret = $envValues['KC_BFF_CLIENT_SECRET']
    $usersPassword = $envValues['KC_DEV_USERS_PASSWORD']
    Invoke-Native docker @('compose', '-f', $composeFile, 'up', '-d', '--build')
} else {
    $composeFile = 'compose.yaml'
    $kcAdminUser = 'admin'; $kcAdminPass = 'admin'               # fixed dev values in compose.yaml
    $expectedSecret = 'dev-only-medlink-bff-secret'              # compose.yaml + application-dev.yml
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
    cmd /c "mvnw.cmd -B verify > `"$testLog`" 2>&1"
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
        -ArgumentList '/c', "set SPRING_PROFILES_ACTIVE=dev&& mvnw.cmd -B spring-boot:run > `"$appLog`" 2>&1"
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
try {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $loginPage = Invoke-WebRequest -Uri "$AppUrl/oauth2/authorization/keycloak" -WebSession $session -UseBasicParsing
    if ($loginPage.Content -notmatch 'action="([^"]*login-actions/authenticate[^"]*)"') { throw 'Keycloak login form not found' }
    $action = [System.Net.WebUtility]::HtmlDecode($Matches[1])
    try {
        Invoke-WebRequest -Uri $action -Method Post -WebSession $session -UseBasicParsing `
            -Body @{ username = 'practitioner.dev'; password = $usersPassword; credentialId = '' } | Out-Null
    } catch {
        # After login Spring redirects to "/", which has no page (404): expected, the session is what matters.
        if (-not $_.Exception.Response) { throw }
    }
    $api = Invoke-WebRequest -Uri "$AppUrl/api/users" -WebSession $session -UseBasicParsing
    if ($api.StatusCode -eq 200) { Write-Ok 'end-to-end login as practitioner.dev works: /api/users returns 200 with the session' }
    else { Write-Bad "after login /api/users returned $($api.StatusCode)" }
} catch {
    Write-Warn "automated login check did not complete ($($_.Exception.Message)). Test it in a browser: $AppUrl/oauth2/authorization/keycloak"
}

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
  Keycloak admin  $KeycloakUrl   (user: $kcAdminUser, password: $(if ($FullStack) { 'KEYCLOAK_ADMIN_PASSWORD in .env' } else { 'admin' }))
  Test users      staff.dev / practitioner.dev / patient.dev   (password: $(if ($FullStack) { 'KC_DEV_USERS_PASSWORD in .env' } else { $DevUsersPassword }))
  Logs            $LogDir

  Stop everything:  powershell -ExecutionPolicy Bypass -File "$Repo\scripts\setup-dev.ps1" -Stop
  Fresh realm:      re-run with -Reset   (deletes Keycloak and app data)
"@
if ($script:Failures -gt 0) { exit 1 }
