# Personal fork setup after build-and-install.ps1. No install, force-stop, uninstall or package disabling.
# Usage: .\launcher\enable-features.ps1 -TvHost <tv ip> [-Port 5555] [-AdbPath <adb.exe>]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9.-]+$')][string]$TvHost,
    [ValidateRange(1, 65535)][int]$Port = 5555,
    [string]$AdbPath = "$env:USERPROFILE\Downloads\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $AdbPath)) { throw "adb.exe was not found: $AdbPath" }
$target = "${TvHost}:$Port"
& $AdbPath connect $target
$state = & $AdbPath -s $target get-state
if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') { throw 'TV is not connected or has not authorised this computer.' }

function Tv([string]$command) {
    $result = & $AdbPath -s $target shell $command
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $command" }
    return ($result -join "`n").Trim()
}
$userId = Tv 'am get-current-user'
if ($userId -notmatch '^\d+$') { throw 'Could not determine the current TV user.' }
$sdkText = Tv 'getprop ro.build.version.sdk'
if ($sdkText -notmatch '^\d+$') { throw 'Could not determine the Android SDK.' }
$sdk = [int]$sdkText
$uri = 'content://com.gathin.tvhome.theme'
$status = Tv "content call --user $userId --uri $uri --method configure_features"
if ($status -notmatch 'configured=true') { throw 'Install the updated personal launcher first (configure_features was not found).' }
if ($status -notmatch 'happ_supported=true') { throw 'Install a supported official Happ app and configure its server first.' }

$backup = Join-Path (Split-Path $PSScriptRoot -Parent) ('backup\features-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$commands = @{
    packages = "pm list packages --user $userId"
    disabled = "pm list packages -d --user $userId"
    properties = 'getprop'
    secure = "settings --user $userId list secure"
    system = "settings --user $userId list system"
    global = 'settings list global'
}
foreach ($entry in $commands.GetEnumerator()) {
    Tv $entry.Value | Set-Content -Encoding UTF8 (Join-Path $backup "$($entry.Key).txt")
}
$status | Set-Content -Encoding UTF8 (Join-Path $backup 'feature-flags.txt')
if ($sdk -ge 33) {
    Tv "cmd appops get --user $userId com.gathin.tvhome ACCESS_RESTRICTED_SETTINGS" |
        Set-Content -Encoding UTF8 (Join-Path $backup 'restricted-settings.txt')
}

Tv "pm grant --user $userId com.gathin.tvhome android.permission.READ_TV_LISTINGS" | Out-Null
if ($sdk -ge 33) {
    Tv "cmd appops set --user $userId com.gathin.tvhome ACCESS_RESTRICTED_SETTINGS allow" | Out-Null
}
$cleanup = if ($sdk -lt 34) { 'true' } else { 'false' }
$configured = Tv "content call --user $userId --uri $uri --method configure_features --extra watch_shelf:b:true --extra watch_network_images:b:true --extra youtube_auto_tunnel:b:true --extra background_cleanup_5min:b:$cleanup"
if ($configured -notmatch 'configured=true') { throw "Feature setup failed: $configured" }
Tv "am start -W --user $userId -n com.gathin.tvhome/.MainActivity"

$old = Tv "settings --user $userId get secure enabled_accessibility_services"
$short = 'com.gathin.tvhome/.AppAutomationService'
$full = 'com.gathin.tvhome/com.gathin.tvhome.AppAutomationService'
$services = @($old -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $short -and $_ -ne $full })
$services += $short
$joined = ($services | Select-Object -Unique) -join ':'
if ($joined -notmatch '^[A-Za-z0-9_./:]+$') { throw 'Unexpected service-list format; existing services were left untouched.' }
Tv "settings --user $userId put secure enabled_accessibility_services $joined" | Out-Null
Tv "settings --user $userId put secure accessibility_enabled 1" | Out-Null
Start-Sleep -Seconds 3
Tv 'dumpsys accessibility' | Select-String -Pattern 'Bound services|Enabled services|Binding services|Crashed services|AppAutomationService' -Context 0,2
$final = Tv "content call --user $userId --uri $uri --method configure_features"
Write-Host $final
Write-Host "Backup: $backup"
if ($final -notmatch 'service_bound=true') {
    throw 'The service is not connected. Check the device policy and accessibility log; do not repeat setup in a loop.'
}
if ($sdk -ge 34) { Write-Host 'Android 14+: background cleanup is unavailable and was left off.' }
Write-Host 'Setup complete. Test Happ manually once, then open a selected Auto VPN app and return to Home.'
