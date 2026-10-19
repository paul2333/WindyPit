param(
    [int]$Clients = 20,
    [int]$Seconds = 60,
    [string]$Tag = "20"
)
$ErrorActionPreference = 'Stop'
$wp = 'C:\Users\ylxyl\OneDrive\Desktop\WindyPit'
$tp = 'C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release'
$dir = Join-Path $wp '.review\windypit-1.8-threaded\dsh-run\20261018-0945'
$smoke = Join-Path $dir 'server-smoke-p65'
$java = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$jcmd = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\jcmd.exe'
$out = Join-Path $dir "P80-load-$Tag-stdout.log"

# refresh artifacts
Copy-Item (Join-Path $wp 'WindSpigot-Server\target\WindSpigot.jar') (Join-Path $smoke 'WindSpigot.jar') -Force
Copy-Item (Join-Path $tp 'base\build\libs\ThePitUltra-build-1.jar') (Join-Path $smoke 'plugins\ThePitUltra.jar') -Force

$jvmArgs = @(
    '--add-opens','java.base/java.lang=ALL-UNNAMED',
    '--add-opens','java.base/java.net=ALL-UNNAMED',
    '--add-opens','java.base/java.nio=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Xms1G','-Xmx2G','-Dcom.mojang.eula.agree=true',
    '-jar','WindSpigot.jar','--noconsole'
)
$process = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $smoke `
    -RedirectStandardOutput $out -RedirectStandardError (Join-Path $dir "P80-load-$Tag-stderr.log") -PassThru
"PID=$($process.Id)" | Set-Content (Join-Path $dir "P80-load-$Tag-pid.txt")

$deadline = (Get-Date).AddSeconds(300)
$started = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Path $out) {
        $text = Get-Content $out -Raw -ErrorAction SilentlyContinue
        if ($text -and $text.Contains('Done (')) { $started = $true; break }
    }
    Start-Sleep -Milliseconds 500
}
"STARTED=$started" | Set-Content (Join-Path $dir "P80-load-$Tag-result.txt")
if (-not $started) {
    if (-not $process.HasExited) { $process.Kill() }
    exit 1
}
Start-Sleep -Seconds 5

$jfr = Join-Path $dir "P80-load-$Tag.jfr"
& $jcmd $process.Id JFR.start "name=windypit-$Tag" settings=profile "duration=$($Seconds + 20)s" "filename=$jfr" |
    Out-File (Join-Path $dir "P80-load-$Tag-jfr-start.txt")

& python (Join-Path $wp 'runtime\tools\load_clients.py') --clients $Clients --seconds $Seconds `
    --output (Join-Path $dir "P80-load-$Tag.json") *> (Join-Path $dir "P80-load-$Tag-client.log")
"CLIENT_EXIT=$LASTEXITCODE" | Add-Content (Join-Path $dir "P80-load-$Tag-result.txt")

& python (Join-Path $dir 'rcon_client.py') 25578 windypit 'windypit-tps' *> (Join-Path $dir "P80-load-$Tag-tps.txt")
"TPS_EXIT=$LASTEXITCODE" | Add-Content (Join-Path $dir "P80-load-$Tag-result.txt")

Start-Sleep -Seconds 5
& $jcmd $process.Id JFR.dump "name=windypit-$Tag" "filename=$jfr" *> (Join-Path $dir "P80-load-$Tag-jfr-dump.txt")
& python (Join-Path $dir 'rcon_client.py') 25578 windypit 'stop' *> (Join-Path $dir "P80-load-$Tag-stop.txt")
$exited = $process.WaitForExit(180000)
"STOP_EXITED=$exited" | Add-Content (Join-Path $dir "P80-load-$Tag-result.txt")
