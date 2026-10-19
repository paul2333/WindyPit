$ErrorActionPreference = 'Stop'
$run = '20261018-0030'
$wp = 'C:\Users\ylxyl\OneDrive\Desktop\WindyPit'
$tp = 'C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release'
$out = Join-Path $wp ".review\windypit-1.8-threaded\dsh-run\$run"
$smoke = Join-Path $out 'server-smoke-plugin'
$thePitJar = Get-ChildItem (Join-Path $tp 'base\build\libs') -Filter 'ThePitUltra-*.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item -Force $thePitJar.FullName (Join-Path $smoke 'plugins\ThePitUltra.jar')

$java = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$stdout = Join-Path $out 'P40-plugin-smoke2-stdout.log'
$stderr = Join-Path $out 'P40-plugin-smoke2-stderr.log'
$p = New-Object System.Diagnostics.Process
$p.StartInfo.FileName = $java
$p.StartInfo.Arguments = '-Xms1G -Xmx2G -Dcom.mojang.eula.agree=true -jar WindSpigot.jar --nogui'
$p.StartInfo.WorkingDirectory = $smoke
$p.StartInfo.UseShellExecute = $false
$p.StartInfo.RedirectStandardInput = $true
$p.StartInfo.RedirectStandardOutput = $true
$p.StartInfo.RedirectStandardError = $true
$null = $p.Start()
$stdoutWriter = [IO.File]::CreateText($stdout)
$stderrWriter = [IO.File]::CreateText($stderr)
$outEvent = Register-ObjectEvent -InputObject $p -EventName OutputDataReceived -Action {
    if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $stdoutWriter
$errEvent = Register-ObjectEvent -InputObject $p -EventName ErrorDataReceived -Action {
    if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
} -MessageData $stderrWriter
$p.BeginOutputReadLine()
$p.BeginErrorReadLine()
"PID=$($p.Id)" | Set-Content (Join-Path $out 'P40-plugin-smoke2-pid.txt')

function Wait-Text([string]$path, [string]$needle, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $path) {
            $text = Get-Content $path -Raw -ErrorAction SilentlyContinue
            if ($text -and $text.Contains($needle)) { return $true }
        }
        if ($p.HasExited) { return $false }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

$ok = Wait-Text $stdout 'Done (' 240
"STARTED=$ok EXITED=$($p.HasExited)" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke2-result.txt')
if ($ok) {
    Start-Sleep -Seconds 6
    $upgraded = (Get-Content $stdout -Raw).Contains('adapter upgraded to WINDYPIT_KERNEL')
    "KERNEL_UPGRADED=$upgraded" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke2-result.txt') -Append
    $jcmd = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\jcmd.exe'
    & $jcmd $p.Id Thread.print *> (Join-Path $out 'P40-plugin-smoke2-thread-dump.txt')
    Select-String -Path (Join-Path $out 'P40-plugin-smoke2-thread-dump.txt') -Pattern 'WindyPit-Region-world-owner' |
        ForEach-Object { $_.Line.Trim() } | Set-Content (Join-Path $out 'P40-plugin-smoke2-owner-threads.txt')

    $p.StandardInput.WriteLine('windypit-tps')
    $p.StandardInput.Flush()
    Start-Sleep -Seconds 3
    $p.StandardInput.WriteLine('stop')
    $p.StandardInput.Flush()
    $exited = $p.WaitForExit(120000)
    "STOP_EXITED=$exited" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke2-result.txt') -Append
} else {
    if (-not $p.HasExited) { $p.Kill() }
}
Start-Sleep -Seconds 2
$stdoutWriter.Flush(); $stdoutWriter.Close()
$stderrWriter.Flush(); $stderrWriter.Close()
Unregister-Event -SourceIdentifier $outEvent.Name -ErrorAction SilentlyContinue
Unregister-Event -SourceIdentifier $errEvent.Name -ErrorAction SilentlyContinue
