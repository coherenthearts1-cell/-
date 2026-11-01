# Keyboard-first Android manager. Windows PowerShell 5.1; no administrator needed.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$adbPath = Join-Path $PSScriptRoot 'platform-tools\adb.exe'

# Encode each argument for Windows CreateProcess, including spaces and trailing backslashes.
function Quote-Argument([string]$Value) {
    '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}
function Run-Adb([string[]]$AdbArgs, [int]$Seconds = 90) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $adbPath
    $info.Arguments = (($AdbArgs | ForEach-Object { Quote-Argument $_ }) -join ' ')
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $info
    try {
        [void]$p.Start()
        $stdout = $p.StandardOutput.ReadToEndAsync()
        $stderr = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($Seconds * 1000)) {
            $p.Kill(); $p.WaitForExit()
            throw 'Operation timed out. A file transfer or installation may be incomplete; check the destination before retrying.'
        }
        $text = ($stdout.GetAwaiter().GetResult() + "`r`n" + $stderr.GetAwaiter().GetResult()).Trim()
        if ($p.ExitCode -ne 0) { throw $text }
        return $text
    } finally { $p.Dispose() }
}
function Check-Connection {
    $state = Run-Adb -AdbArgs @('-d','get-state')
    if ($state.Trim() -ne 'device') { throw 'Connect exactly one authorized USB Android device.' }
}
function Read-Package {
    $name = (Read-Host 'Exact package name (example: com.example.app)').Trim()
    if ($name -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') { throw 'Invalid package name.' }
    return $name
}
function Read-SharedPath([string]$Prompt) {
    $path = (Read-Host $Prompt).Trim()
    # Limit file operations to shared storage, reject traversal and shell metacharacters.
    if ($path -notmatch '^/sdcard/[\p{L}\p{N}_ .()/+@,\-]*$' -or $path -match '(^|/)\.\.(/|$)' -or $path -match '[\r\n]') {
        throw 'Use an absolute /sdcard/ path without .. or shell punctuation. Example: /sdcard/Download/file.pdf'
    }
    return $path
}
function Confirm-Action([string]$Description) {
    Write-Host $Description
    return ((Read-Host 'Type YES to confirm; anything else cancels') -ceq 'YES')
}
try {
    if (-not (Test-Path -LiteralPath $adbPath)) {
        Write-Host 'First run option 1 in Run-Phone-Check.cmd to download official Google tools and authorize the phone.'
        $null = Read-Host 'Press Enter to return'
        exit
    }
    $null = Run-Adb -AdbArgs @('start-server')
    while ($true) {
        Write-Host "`r`nPHONE MANAGER - keyboard menu"
        Write-Host '1. Create performance report'
        Write-Host '2. List third-party app package names'
        Write-Host '3. List files in a shared-storage folder'
        Write-Host '4. Copy a phone file/folder to this PC (pull)'
        Write-Host '5. Copy a PC file to the phone (push)'
        Write-Host '6. Install a local APK (no automatic replacement)'
        Write-Host '7. Uninstall a third-party app (deletes its app data)'
        Write-Host '8. Open Android storage settings on the phone'
        Write-Host '9. Open Android application settings on the phone'
        Write-Host '0. Exit'
        $choice = Read-Host 'Enter a number'
        if ($choice -eq '0') { break }
        try {
            Check-Connection
            switch ($choice) {
                '1' { & (Join-Path $PSScriptRoot 'Check-Phone.ps1') }
                '2' {
                    $apps = Run-Adb -AdbArgs @('-d','shell','pm list packages -3')
                    $filter = Read-Host 'Optional text filter (Enter lists all)'
                    if ($filter) { $apps = (($apps -split "`n" | Where-Object { $_.IndexOf($filter,[StringComparison]::OrdinalIgnoreCase) -ge 0 }) -join "`r`n") }
                    Write-Host $apps
                    $apps | Out-File -LiteralPath (Join-Path $PSScriptRoot 'App-list.txt') -Encoding utf8
                }
                '3' {
                    $remote = Read-SharedPath 'Phone folder (example: /sdcard/Download)'
                    Write-Host (Run-Adb -AdbArgs @('-d','shell',("ls -la -- '" + $remote + "'")))
                }
                '4' {
                    $remote = Read-SharedPath 'Source phone file or folder (/sdcard/...)'
                    $local = (Read-Host 'Destination PC folder (absolute path, no surrounding quotes)').Trim()
                    if (-not [IO.Path]::IsPathRooted($local) -or -not (Test-Path -LiteralPath $local -PathType Container)) { throw 'Choose an existing absolute destination folder.' }
                    if (Confirm-Action "Copy $remote to $local ? Existing matching files may be overwritten. Large transfers can take time.") {
                        Write-Host (Run-Adb -AdbArgs @('-d','pull',$remote,$local) -Seconds 1800)
                    }
                }
                '5' {
                    $local = (Read-Host 'Source PC file (absolute path, no surrounding quotes)').Trim()
                    if (-not [IO.Path]::IsPathRooted($local) -or -not (Test-Path -LiteralPath $local -PathType Leaf)) { throw 'Choose an existing absolute file path.' }
                    $remote = Read-SharedPath 'Destination phone path (/sdcard/Download/filename.ext or existing folder)'
                    if (Confirm-Action "Copy $local to $remote ? An existing matching file can be overwritten.") {
                        Write-Host (Run-Adb -AdbArgs @('-d','push',$local,$remote) -Seconds 1800)
                    }
                }
                '6' {
                    $apk = (Read-Host 'Absolute path of a trusted APK, no surrounding quotes').Trim()
                    if (-not [IO.Path]::IsPathRooted($apk) -or -not (Test-Path -LiteralPath $apk -PathType Leaf) -or [IO.Path]::GetExtension($apk) -ine '.apk') { throw 'Choose an existing APK file.' }
                    if (Confirm-Action "Install $apk on your phone? Only install an APK you trust. Existing apps will not be replaced.") {
                        Write-Host (Run-Adb -AdbArgs @('-d','install',$apk) -Seconds 300)
                    }
                }
                '7' {
                    $package = Read-Package
                    $apps = (Run-Adb -AdbArgs @('-d','shell','pm list packages -3')) -split '\r?\n'
                    if ($apps -notcontains ('package:'+$package)) { throw 'Package not found in third-party app list. System apps are not managed here.' }
                    if (Confirm-Action "UNINSTALL $package and DELETE its private app data. Save any needed data first. This is not a backup.") {
                        Write-Host (Run-Adb -AdbArgs @('-d','uninstall',$package) -Seconds 180)
                    }
                }
                '8' { Write-Host (Run-Adb -AdbArgs @('-d','shell','am start -a android.settings.INTERNAL_STORAGE_SETTINGS')) }
                '9' { Write-Host (Run-Adb -AdbArgs @('-d','shell','am start -a android.settings.APPLICATION_SETTINGS')) }
                default { Write-Host 'Choose a number from the menu.' }
            }
        } catch { Write-Host ('ERROR: ' + $_.Exception.Message) }
        $null = Read-Host 'Press Enter to return to menu'
    }
} catch { Write-Host ('ERROR: ' + $_.Exception.Message); $null = Read-Host 'Press Enter to close' }
