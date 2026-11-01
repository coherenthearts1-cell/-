# Read-only Samsung/Android diagnostics. Compatible with Windows PowerShell 5.1.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = $PSScriptRoot
$adbPath = Join-Path $base 'platform-tools\adb.exe'
$reportPath = Join-Path $base ('Phone-Report-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')

function Invoke-Adb([string]$Arguments, [int]$Seconds = 30) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $adbPath
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($Seconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            return [pscustomobject]@{ Code = -1; Text = 'TIMEOUT: command did not finish.' }
        }
        $out = $stdout.GetAwaiter().GetResult()
        $err = $stderr.GetAwaiter().GetResult()
        return [pscustomobject]@{ Code = $process.ExitCode; Text = ($out + "`r`n" + $err).Trim() }
    } finally { $process.Dispose() }
}
function Add-Section([string]$Title, [string]$Arguments) {
    Write-Host ('Checking: ' + $Title)
    $result = Invoke-Adb $Arguments
    @("`r`n===== $Title =====", ('Exit code: ' + $result.Code), $result.Text) |
        Out-File -LiteralPath $reportPath -Encoding utf8 -Append
}
try {
    Write-Host 'Android phone performance check - read only'
    Write-Host 'No messages, photos, contacts, files, location, or device serial are collected.'
    Write-Host 'The report includes app/process names that are running or consuming resources.'
    Write-Host 'Keep the phone connected and unlocked. Do not run during a call or payment.'
    Write-Host 'Use the phone normally before running; do not restart it just for this check.'
    if (-not (Test-Path -LiteralPath $adbPath)) {
        Write-Host 'Google Android Platform Tools are needed (downloaded into this folder only).'
        $choice = Read-Host 'Download from dl.google.com now? Type Y to continue'
        if ($choice -notmatch '^[Yy]$') { throw 'Download cancelled. Nothing was checked.' }
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $zipPath = Join-Path $base 'platform-tools-download.zip'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zipPath
        Expand-Archive -LiteralPath $zipPath -DestinationPath $base -Force
        Remove-Item -LiteralPath $zipPath
        if (-not (Test-Path -LiteralPath $adbPath)) { throw 'ADB was not found after extraction.' }
    }
    $null = Invoke-Adb 'start-server'
    Write-Host 'Unlock your phone and accept USB debugging for your own computer if asked.'
    $null = Read-Host 'Press Enter after the phone is connected and authorization is accepted'
    $state = Invoke-Adb '-d get-state'
    if ($state.Code -ne 0 -or $state.Text.Trim() -ne 'device') {
        throw 'No single authorized USB phone found. Check the data cable, USB debugging and authorization. Disconnect other Android devices. A Samsung USB driver may be needed. No report created.'
    }
    @('PHONE PERFORMANCE REPORT v1', ('Report time (PC local): ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')), 'Read-only diagnostics; no remediation was performed.', 'App/process names may appear. Review this file before sharing.', 'Unsupported or permission-denied sections are normal on some Android versions.', 'This is a snapshot, not a full hardware test or malware scan.') |
        Out-File -LiteralPath $reportPath -Encoding utf8
    Add-Section 'Manufacturer' '-d shell getprop ro.product.manufacturer'
    Add-Section 'Model' '-d shell getprop ro.product.model'
    Add-Section 'Android version' '-d shell getprop ro.build.version.release'
    Add-Section 'Android API level' '-d shell getprop ro.build.version.sdk'
    Add-Section 'Security patch' '-d shell getprop ro.build.version.security_patch'
    Add-Section 'One UI numeric version (may be empty)' '-d shell getprop ro.build.version.oneui'
    Add-Section 'Uptime and load' '-d shell uptime'
    Add-Section 'Internal storage space' '-d shell df -h /data /sdcard'
    Add-Section 'Memory totals (kB)' '-d shell cat /proc/meminfo'
    Add-Section 'Memory pressure' '-d shell cat /proc/pressure/memory'
    Add-Section 'CPU pressure' '-d shell cat /proc/pressure/cpu'
    Add-Section 'Storage IO pressure' '-d shell cat /proc/pressure/io'
    Add-Section 'Battery (temperature is tenths of a degree Celsius)' '-d shell dumpsys battery'
    Add-Section 'Battery saver (1 enabled, 0 disabled)' '-d shell settings get global low_power'
    Add-Section 'Thermal status (0 none; higher values indicate throttling severity)' '-d shell dumpsys thermalservice'
    Add-Section 'CPU usage by process, sample 1' '-d shell dumpsys cpuinfo'
    Add-Section 'Memory use by process (summary)' '-d shell dumpsys meminfo --oom'
    Write-Host 'Waiting for a second CPU sample (5 seconds).'
    Start-Sleep -Seconds 5
    Add-Section 'CPU usage by process, sample 2' '-d shell dumpsys cpuinfo'
    Add-Section 'Background process limit (-1 is system default)' '-d shell settings get global background_process_limit'
    Add-Section 'Window animation scale' '-d shell settings get global window_animation_scale'
    Add-Section 'Transition animation scale' '-d shell settings get global transition_animation_scale'
    Add-Section 'Animator duration scale' '-d shell settings get global animator_duration_scale'
    Write-Host ''
    Write-Host 'Done. No phone settings were changed.'
    Write-Host ('Report saved: ' + $reportPath)
    Write-Host 'Review the text report, then attach it in the chat for analysis.'
    Write-Host 'Afterwards, turn off USB debugging if you do not need it.'
    Start-Process notepad.exe -ArgumentList ('"' + $reportPath + '"')
} catch {
    Write-Host ('ERROR: ' + $_.Exception.Message)
    Write-Host 'If a partial report exists, you can still share it after reviewing it.'
} finally {
    $null = Read-Host 'Press Enter to close'
}
