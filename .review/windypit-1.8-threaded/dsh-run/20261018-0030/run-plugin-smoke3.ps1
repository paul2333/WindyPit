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
$stdout = Join-Path $out 'P40-plugin-smoke3-stdout.log'
$stderr = Join-Path $out 'P40-plugin-smoke3-stderr.log'
$p = Start-Process -FilePath $java -ArgumentList @('-Xms1G','-Xmx2G','-Dcom.mojang.eula.agree=true','-jar','WindSpigot.jar','--noconsole') `
    -WorkingDirectory $smoke -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
"PID=$($p.Id)" | Set-Content (Join-Path $out 'P40-plugin-smoke3-pid.txt')

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
"STARTED=$ok EXITED=$($p.HasExited)" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke3-result.txt')
if ($ok) {
    Start-Sleep -Seconds 8
    $text = Get-Content $stdout -Raw
    "KERNEL_UPGRADED=$($text.Contains('adapter upgraded to WINDYPIT_KERNEL'))" |
        Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke3-result.txt') -Append
    "BOOTSTRAP_FALLBACK=$($text.Contains('mode: BUKKIT_FALLBACK'))" |
        Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke3-result.txt') -Append
    $jcmd = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\jcmd.exe'
    & $jcmd $p.Id Thread.print *> (Join-Path $out 'P40-plugin-smoke3-thread-dump.txt')
    Select-String -Path (Join-Path $out 'P40-plugin-smoke3-thread-dump.txt') -Pattern 'WindyPit-Region-world-owner' |
        ForEach-Object { $_.Line.Trim() } | Set-Content (Join-Path $out 'P40-plugin-smoke3-owner-threads.txt')

    $rcon = Join-Path $out 'rcon_client.py'
    & python $rcon 25578 windypit 'windypit-tps' *> (Join-Path $out 'P40-plugin-smoke3-tps.txt')
    "RCON_TPS_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke3-result.txt') -Append
    & python $rcon 25578 windypit 'stop' *> (Join-Path $out 'P40-plugin-smoke3-stop.txt')
    "RCON_STOP_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke3-result.txt') -Append
    $exited = $p.WaitForExit(120000)
    "STOP_EXITED=$exited" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke3-result.txt') -Append
} else {
    if (-not $p.HasExited) { $p.Kill() }
}

