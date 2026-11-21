#requires -Version 7.0
# Requires PowerShell 7+ for ForEach-Object -Parallel. Install: Windows `winget install Microsoft.PowerShell`,
# macOS `brew install powershell`. Run with `pwsh ./scripts/demo-concurrent.ps1 ...`.
[CmdletBinding()]
param(
    [ValidateSet('Login','Enrollment')][string]$Scenario = 'Login',
    [string]$Base = 'http://127.0.0.1:18080/api/v1',
    [string]$Origin = 'http://localhost:5173',
    [string]$Password = 'Demo-password1!'
)
$ErrorActionPreference = 'Stop'
# Dedicated demo DB only. Creates sessions/tickets/enrollments; never resets data.
# Helpers are loaded in each parallel runspace. Tokens remain in memory only.
$helpers = @'
function Request($Method, $Path, $Body = $null, $Access = '', $Cookie = '') {
    $headers = @{Origin=$Origin; 'X-CSRF-Protection'='1'}
    if ($Access) { $headers.Authorization = "Bearer $Access" }
    if ($Cookie) { $headers.Cookie = $Cookie }
    $args = @{Uri="$Base$Path"; Method=$Method; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=30}
    if ($null -ne $Body) { $args.ContentType='application/json'; $args.Body=($Body | ConvertTo-Json -Compress) }
    $r = Invoke-WebRequest @args
    $json = if ($r.Content) { $r.Content | ConvertFrom-Json } else { $null }
    [pscustomobject]@{ Status=[int]$r.StatusCode; Json=$json; Headers=$r.Headers }
}
function Expect($Response, $Status) {
    if ($Response.Status -ne $Status) { throw "Expected HTTP $Status, received $($Response.Status)" }
}
function CookieValue($Response, $Name) {
    foreach ($line in $Response.Headers['Set-Cookie']) {
        if ($line -match ("^" + [regex]::Escape($Name) + "=([^;]+)")) { return "$Name=$($Matches[1])" }
    }
    throw 'Required cookie missing'
}
function WaitTicket($Id, $Access = '', $Cookie = '') {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $r = Request GET "/tickets/$Id" $null $Access $Cookie
        Expect $r 200
        if ($r.Json.status -in @('SUCCEEDED','REJECTED')) { return $r.Json }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Ticket $Id timed out"
}
function Login($UserId) {
    $r = Request POST '/auth/login' @{userId=$UserId;password=$Password}
    Expect $r 202
    $id = $r.Json.ticketId
    $proof = CookieValue $r '__Host-crs_login'
    $ticket = WaitTicket $id '' $proof
    if ($ticket.result.code -ne 'OK') { throw "Login ticket $id did not succeed" }
    $tokens = Request POST "/auth/login-tickets/$id/tokens" $null '' $proof
    Expect $tokens 200
    [pscustomobject]@{UserId=$UserId; Access=$tokens.Json.accessToken; TicketId=$id}
}
'@
. ([scriptblock]::Create($helpers))
$admin = Login 'A1001'
$settings = Request GET '/admin/settings' $null $admin.Access
Expect $settings 200
$oldN = $settings.Json.concurrencyLimit
$scenarioFailed = $true
try {
    Expect (Request PUT '/admin/settings' @{concurrencyLimit=2} $admin.Access) 200
    if ($Scenario -eq 'Login') {
        $results = 1..5 | ForEach-Object -Parallel {
            $ErrorActionPreference='Stop'
            $Base=$using:Base; $Origin=$using:Origin; $Password=$using:Password
            . ([scriptblock]::Create($using:helpers))
            $login = Login ([string](20260000+$_))
            [pscustomobject]@{UserId=$login.UserId; TicketId=$login.TicketId; Code='OK'}
        } -ThrottleLimit 5
        if (@($results).Count -ne 5) { throw 'Five successful logins required' }
    } else {
        $course = Request GET '/courses/DEMO01' $null $admin.Access
        Expect $course 200
        if ($course.Json.capacity -ne 3 -or $course.Json.activeEnrollmentCount -ne 0 -or $course.Json.status -ne 'OPEN') {
            throw 'S2 requires fresh OPEN DEMO01: capacity=3, activeEnrollmentCount=0'
        }
        # Finish all login/admission work before the enrollment burst.
        $students = 1..30 | ForEach-Object -Parallel {
            $ErrorActionPreference='Stop'
            $Base=$using:Base; $Origin=$using:Origin; $Password=$using:Password
            . ([scriptblock]::Create($using:helpers))
            $login = Login ([string](20260000+$_))
            $r = Request POST '/admission' $null $login.Access
            if ($r.Status -eq 202) {
                $ticket = WaitTicket $r.Json.ticketId $login.Access
                if ($ticket.result.code -ne 'OK') { throw 'Admission did not succeed' }
            } else { Expect $r 200 }
            $login
        } -ThrottleLimit 30
        if (@($students).Count -ne 30) { throw 'Thirty admitted sessions required' }
        $results = $students | ForEach-Object -Parallel {
            $ErrorActionPreference='Stop'
            $Base=$using:Base; $Origin=$using:Origin
            . ([scriptblock]::Create($using:helpers))
            $r = Request POST '/me/enrollments' @{courseCode='DEMO01'} $_.Access
            Expect $r 202
            $ticket = WaitTicket $r.Json.ticketId $_.Access
            [pscustomobject]@{UserId=$_.UserId; TicketId=$r.Json.ticketId; Code=$ticket.result.code}
        } -ThrottleLimit 30
        if (@($results).Count -ne 30 -or @($results | Where-Object Code -eq 'OK').Count -ne 3 -or
            @($results | Where-Object Code -eq 'E07').Count -ne 27) { throw 'Expected exactly 3 OK and 27 E07' }
    }
    $results | Sort-Object {[long]$_.TicketId} | Format-Table UserId,TicketId,Code
    Write-Host "$Scenario passed with N=2."
    $scenarioFailed = $false
} finally {
    # A failed restore must not mask the scenario's own error; it still fails an otherwise passing run.
    try {
        Expect (Request PUT '/admin/settings' @{concurrencyLimit=$oldN} $admin.Access) 200
    } catch {
        $message = "Could not restore concurrencyLimit=${oldN}: $($_.Exception.Message)"
        if ($scenarioFailed) { Write-Warning $message } else { throw $message }
    }
}
