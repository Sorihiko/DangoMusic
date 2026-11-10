# Dango Music: read what other apps are playing through Windows' media controls (SMTC).
# One JSON line on stdout whenever it changes (and every 5 s); commands come in on stdin.
# Non-ASCII is written as \uXXXX so the console code page does not matter. Exits when stdin closes.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
function Await($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); [void]$task.Wait(3000); if ($task.IsCompleted) { $task.Result } else { $null } }
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$mgr = Await ($mgrType::RequestAsync()) $mgrType
if ($mgr -eq $null) { exit 2 }
$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())
$pending = $stdin.ReadLineAsync()
$cur = $null
$last = ''
$lastAt = [DateTime]::MinValue
while ($true) {
    if ($pending.IsCompleted) {
        $cmd = $pending.Result
        if ($cmd -eq $null) { break }
        if ($cur -ne $null) {
            try {
                if ($cmd -eq 'toggle') { $null = $cur.TryTogglePlayPauseAsync() }
                elseif ($cmd -eq 'next') { $null = $cur.TrySkipNextAsync() }
                elseif ($cmd -eq 'prev') { $null = $cur.TrySkipPreviousAsync() }
                elseif ($cmd -like 'seek *') { $null = $cur.TryChangePlaybackPositionAsync([long]$cmd.Substring(5) * 10000) }
            } catch {}
        }
        $pending = $stdin.ReadLineAsync()
    }
    $out = '{}'
    try {
        # Prefer a session that is actually playing; otherwise whatever Windows calls current
        $s = $null
        foreach ($c in $mgr.GetSessions()) { if ([string]$c.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { $s = $c; break } }
        if ($s -eq $null) { $s = $mgr.GetCurrentSession() }
        $cur = $s
        if ($s -ne $null) {
            $p = Await ($s.TryGetMediaPropertiesAsync()) $propType
            $tl = $s.GetTimelineProperties()
            if ($p -ne $null) {
                $o = [ordered]@{
                    app = [string]$s.SourceAppUserModelId
                    title = [string]$p.Title
                    artist = [string]$p.Artist
                    album = [string]$p.AlbumTitle
                    status = [string]$s.GetPlaybackInfo().PlaybackStatus
                    pos = [long]$tl.Position.TotalMilliseconds
                    end = [long]$tl.EndTime.TotalMilliseconds
                    at = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
                }
                $out = [regex]::Replace((ConvertTo-Json $o -Compress), '[^\x00-\x7F]', { param($m) '\u{0:x4}' -f [int][char]$m.Value })
            }
        }
    } catch { $out = '{}' }
    $now = [DateTime]::UtcNow
    if ($out -ne $last -or ($now - $lastAt).TotalSeconds -ge 5) {
        [Console]::Out.WriteLine($out)
        [Console]::Out.Flush()
        $last = $out
        $lastAt = $now
    }
    Start-Sleep -Milliseconds 300
}
