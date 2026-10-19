$ErrorActionPreference = 'Stop'
$run = '20261018-0030'
$root = 'C:\Users\ylxyl\OneDrive\Desktop\WindyPit'
$out = Join-Path $root ".review\windypit-1.8-threaded\dsh-run\$run"
$smoke = Join-Path $out 'server-smoke'
$template = Join-Path $root 'runtime\test-data\windspigot-jfr-20261012T015227Z'
New-Item -ItemType Directory -Force -Path $smoke | Out-Null

# Copy a disposable world/config template (never modify the user's runtime data).
foreach ($name in @('world','world_nether','world_the_end','plugins','logs')) {
    $src = Join-Path $template $name
    $dest = Join-Path $smoke $name
    if ((Test-Path $src) -and -not (Test-Path $dest)) { Copy-Item -Recurse -Force $src $dest }
}
foreach ($file in @('bukkit.yml','spigot.yml','paper.yml','taco.yml','knockback.yml','commands.yml','help.yml','ops.json','whitelist.json','banned-players.json','banned-ips.json','usercache.json','eula.txt')) {
    $src = Join-Path $template $file
    if (Test-Path $src) { Copy-Item -Force $src (Join-Path $smoke $file) }
}

# WindSpigot owns the settings: section of windypit.yml; append WindyPit's own sections.
$windypit = Join-Path $smoke 'windypit.yml'
Copy-Item -Force (Join-Path $template 'windypit.yml') $windypit
$extra = @'

# ---- WindyPit REGION acceptance profile (added by DSH run 20261018-0030) ----
threading:
  mode: region
  arena-owner: primary
  region-threads: 2
  entity-threads: auto
  compute-threads: auto
  persistence-threads: bounded

features:
  async-entity-tracking: true
  async-visual-projection: true
  async-pathfinding: true
  async-persistence: true
  mechanism-pruning: true

pruning:
  natural-spawning: true
  hostile-mob-spawning: true
  weather-and-daylight: true
  farming-and-furnaces: true
  villagers: true
  redstone-and-hoppers: true
  random-tick-no-player-regions: true
  non-arena-entity-maintenance: true
  arena-worlds:
    - world

rollback:
  force-primary-thread-arena: false
  disable-async-tracking: false
  disable-region-scheduler: false
'@
Add-Content -Path $windypit -Value $extra -Encoding UTF8

# Disposable server.properties: dedicated ports, RCON for command evidence.
$props = Get-Content (Join-Path $template 'server.properties')
$props = $props | ForEach-Object {
    if ($_ -match '^server-port=') { 'server-port=8822' }
    elseif ($_ -match '^enable-rcon=') { 'enable-rcon=true' }
    elseif ($_ -match '^rcon\.port=') { 'rcon.port=25577' }
    elseif ($_ -match '^rcon\.password=') { 'rcon.password=windypit' }
    elseif ($_ -match '^broadcast-rcon-to-ops=') { 'broadcast-rcon-to-ops=false' }
    elseif ($_ -match '^online-mode=') { 'online-mode=false' }
    elseif ($_ -match '^view-distance=') { 'view-distance=4' }
    elseif ($_ -match '^max-players=') { 'max-players=8' }
    else { $_ }
}
if (-not ($props -match '^enable-rcon=')) { $props += 'enable-rcon=true' }
if (-not ($props -match '^rcon\.port=')) { $props += 'rcon.port=25577' }
if (-not ($props -match '^rcon\.password=')) { $props += 'rcon.password=windypit' }
if (-not ($props -match '^broadcast-rcon-to-ops=')) { $props += 'broadcast-rcon-to-ops=false' }
Set-Content -Path (Join-Path $smoke 'server.properties') -Value $props -Encoding ASCII

$java = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$stdout = Join-Path $out 'P10-live-smoke-stdout.log'
$stderr = Join-Path $out 'P10-live-smoke-stderr.log'
$p = Start-Process -FilePath $java -ArgumentList @('-Xms1G','-Xmx2G','-Dcom.mojang.eula.agree=true','-jar','WindSpigot.jar','--noconsole') `
    -WorkingDirectory $smoke -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
"PID=$($p.Id)" | Set-Content (Join-Path $out 'P10-live-smoke-pid.txt')

function Wait-Log([string]$path, [string]$needle, [int]$seconds) {
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

function Read-RconPacket($stream) {
    $lenBytes = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) {
        $n = $stream.Read($lenBytes, $read, 4 - $read)
        if ($n -le 0) { throw 'rcon closed' }
        $read += $n
    }
    $len = [BitConverter]::ToInt32($lenBytes, 0)
    $body = New-Object byte[] $len
    $read = 0
    while ($read -lt $len) {
        $n = $stream.Read($body, $read, $len - $read)
        if ($n -le 0) { throw 'rcon closed' }
        $read += $n
    }
    $id = [BitConverter]::ToInt32($body, 0)
    $type = [BitConverter]::ToInt32($body, 4)
    $text = [Text.Encoding]::UTF8.GetString($body, 8, $len - 10)
    return @{ id = $id; type = $type; text = $text }
}

function Send-Rcon($stream, [int]$id, [int]$type, [string]$text) {
    $payload = [Text.Encoding]::UTF8.GetBytes($text)
    $len = 4 + 4 + $payload.Length + 2
    $buf = New-Object byte[] (4 + $len)
    [BitConverter]::GetBytes([int]$len).CopyTo($buf, 0)
    [BitConverter]::GetBytes([int]$id).CopyTo($buf, 4)
    [BitConverter]::GetBytes([int]$type).CopyTo($buf, 8)
    $payload.CopyTo($buf, 12)
    $stream.Write($buf, 0, $buf.Length)
    $stream.Flush()
}

$ok = Wait-Log $stdout 'Done (' 180
"STARTED=$ok EXITED=$($p.HasExited)" | Tee-Object -FilePath (Join-Path $out 'P10-live-smoke-result.txt')

if ($ok) {
    # A real protocol-47 client joins, moves and disconnects. This makes the production
    # WorldTicker build snapshots, the owner domains plan visibility and the primary thread
    # apply the plans, so the counters below prove useful production work, not idle threads.
    $probe = Join-Path $root 'runtime\tools\protocol_probe.py'
    $probeOut = Join-Path $out 'P10-protocol47-probe.json'
    & python $probe --host 127.0.0.1 --port 8822 --username DshProbe --timeout 20 --output $probeOut *>&1 |
        Tee-Object -FilePath (Join-Path $out 'P10-protocol47-probe.log')
    "PROBE_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P10-live-smoke-result.txt') -Append
    Start-Sleep -Seconds 3

    # Thread dump proves the per-world owner threads exist on the live server.
    $jcmd = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\jcmd.exe'
    & $jcmd $p.Id Thread.print *> (Join-Path $out 'P10-live-smoke-thread-dump.txt')
    $ownerThreads = Select-String -Path (Join-Path $out 'P10-live-smoke-thread-dump.txt') -Pattern 'WindyPit-Region-world-owner' | ForEach-Object { $_.Line.Trim() }
    "OWNER_THREADS=$($ownerThreads -join ' | ')" | Tee-Object -FilePath (Join-Path $out 'P10-live-smoke-result.txt') -Append

    $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 25577)
    $stream = $client.GetStream()
    Send-Rcon $stream 1 3 'windypit'
    $login = Read-RconPacket $stream
    "RCON_LOGIN_ID=$($login.id)" | Tee-Object -FilePath (Join-Path $out 'P10-live-smoke-result.txt') -Append
    Send-Rcon $stream 2 2 'windypit-tps'
    $tps = Read-RconPacket $stream
    $tps.text | Set-Content (Join-Path $out 'P10-windypit-tps.txt') -Encoding UTF8
    "RCON_TPS_LEN=$($tps.text.Length)" | Tee-Object -FilePath (Join-Path $out 'P10-live-smoke-result.txt') -Append
    Send-Rcon $stream 3 2 'stop'
    try { $null = Read-RconPacket $stream } catch { }
    $client.Close()
    $exited = $p.WaitForExit(90000)
    "STOP_EXITED=$exited EXITCODE=$($p.ExitCode)" | Tee-Object -FilePath (Join-Path $out 'P10-live-smoke-result.txt') -Append
} else {
    if (-not $p.HasExited) { $p.Kill() }
}
