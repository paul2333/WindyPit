$ErrorActionPreference = 'Stop'
$run = '20261018-0030'
$wp = 'C:\Users\ylxyl\OneDrive\Desktop\WindyPit'
$tp = 'C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release'
$out = Join-Path $wp ".review\windypit-1.8-threaded\dsh-run\$run"
$smoke = Join-Path $out 'server-smoke-plugin'
$template = Join-Path $wp 'runtime\test-data\windspigot-jfr-20261012T015227Z'
New-Item -ItemType Directory -Force -Path $smoke | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $smoke 'plugins') | Out-Null

foreach ($name in @('world','world_nether','world_the_end')) {
    $src = Join-Path $template $name
    $dest = Join-Path $smoke $name
    if ((Test-Path $src) -and -not (Test-Path $dest)) { Copy-Item -Recurse -Force $src $dest }
}
foreach ($file in @('bukkit.yml','spigot.yml','paper.yml','taco.yml','knockback.yml','commands.yml','help.yml','ops.json','whitelist.json','banned-players.json','banned-ips.json','usercache.json','eula.txt')) {
    $src = Join-Path $template $file
    if (Test-Path $src) { Copy-Item -Force $src (Join-Path $smoke $file) }
}
Copy-Item -Force (Join-Path $wp 'WindSpigot-Server\target\WindSpigot.jar') (Join-Path $smoke 'WindSpigot.jar')

# Plugins required by ThePitUltra plugin.yml plus its bundled ProtocolLib target.
$thePitJar = Get-ChildItem (Join-Path $tp 'base\build\libs') -Filter 'ThePitUltra-*.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $thePitJar) { throw 'ThePitUltra jar not found; run :base:build first' }
Copy-Item -Force $thePitJar.FullName (Join-Path $smoke 'plugins\ThePitUltra.jar')
Copy-Item -Force (Join-Path $tp 'libs\ProtocolLib.jar') (Join-Path $smoke 'plugins\ProtocolLib.jar')
$luck = 'C:\Users\ylxyl\OneDrive\Desktop\server-latest\server\plugins\LuckPerms-Bukkit-5.5.81.jar'
$papi = 'C:\Users\ylxyl\OneDrive\Desktop\server-latest\server\plugins\PlaceholderAPI-2.12.3.jar'
if (Test-Path $luck) { Copy-Item -Force $luck (Join-Path $smoke 'plugins\LuckPerms.jar') } else { throw "missing $luck" }
if (Test-Path $papi) { Copy-Item -Force $papi (Join-Path $smoke 'plugins\PlaceholderAPI.jar') } else { throw "missing $papi" }

$windypit = Join-Path $smoke 'windypit.yml'
Copy-Item -Force (Join-Path $template 'windypit.yml') $windypit
$extra = @'

# ---- WindyPit REGION acceptance profile (plugin cross-repo smoke) ----
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

$props = Get-Content (Join-Path $template 'server.properties')
$props = $props | ForEach-Object {
    if ($_ -match '^server-port=') { 'server-port=8823' }
    elseif ($_ -match '^enable-rcon=') { 'enable-rcon=true' }
    elseif ($_ -match '^rcon\.port=') { 'rcon.port=25578' }
    elseif ($_ -match '^rcon\.password=') { 'rcon.password=windypit' }
    elseif ($_ -match '^broadcast-rcon-to-ops=') { 'broadcast-rcon-to-ops=false' }
    elseif ($_ -match '^online-mode=') { 'online-mode=false' }
    elseif ($_ -match '^view-distance=') { 'view-distance=4' }
    elseif ($_ -match '^max-players=') { 'max-players=8' }
    else { $_ }
}
foreach ($line in @('enable-rcon=true','rcon.port=25578','rcon.password=windypit','broadcast-rcon-to-ops=false')) {
    $key = ($line -split '=')[0]
    if (-not ($props -match ("^" + [regex]::Escape($key) + "="))) { $props += $line }
}
Set-Content -Path (Join-Path $smoke 'server.properties') -Value $props -Encoding ASCII

$java = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$stdout = Join-Path $out 'P40-plugin-smoke-stdout.log'
$stderr = Join-Path $out 'P40-plugin-smoke-stderr.log'
$p = Start-Process -FilePath $java -ArgumentList @('-Xms1G','-Xmx2G','-Dcom.mojang.eula.agree=true','-jar','WindSpigot.jar','--noconsole') `
    -WorkingDirectory $smoke -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
"PID=$($p.Id)" | Set-Content (Join-Path $out 'P40-plugin-smoke-pid.txt')

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
    while ($read -lt 4) { $n = $stream.Read($lenBytes, $read, 4 - $read); if ($n -le 0) { throw 'rcon closed' }; $read += $n }
    $len = [BitConverter]::ToInt32($lenBytes, 0)
    $body = New-Object byte[] $len
    $read = 0
    while ($read -lt $len) { $n = $stream.Read($body, $read, $len - $read); if ($n -le 0) { throw 'rcon closed' }; $read += $n }
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
    $stream.Write($buf, 0, $buf.Length); $stream.Flush()
}

$ok = Wait-Log $stdout 'Done (' 240
$enabled = $false
if (Test-Path $stdout) {
    $text = Get-Content $stdout -Raw
    $enabled = $text.Contains('WindyPit bootstrap initialized') -and $text.Contains('ThePit')
}
"STARTED=$ok PLUGIN_BOOTSTRAP=$enabled EXITED=$($p.HasExited)" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke-result.txt')
if ($ok) {
    $jcmd = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\jcmd.exe'
    & $jcmd $p.Id Thread.print *> (Join-Path $out 'P40-plugin-smoke-thread-dump.txt')
    Select-String -Path (Join-Path $out 'P40-plugin-smoke-thread-dump.txt') -Pattern 'WindyPit-Region-world-owner' |
        ForEach-Object { $_.Line.Trim() } | Set-Content (Join-Path $out 'P40-plugin-smoke-owner-threads.txt')

    $probe = Join-Path $wp 'runtime\tools\protocol_probe.py'
    & python $probe --host 127.0.0.1 --port 8823 --username PitProbe --timeout 25 `
        --output (Join-Path $out 'P40-plugin-protocol47-probe.json') *>&1 |
        Tee-Object -FilePath (Join-Path $out 'P40-plugin-protocol47-probe.log')
    "PROBE_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke-result.txt') -Append
    Start-Sleep -Seconds 3

    $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 25578)
    $stream = $client.GetStream()
    Send-Rcon $stream 1 3 'windypit'
    $null = Read-RconPacket $stream
    Send-Rcon $stream 2 2 'windypit-tps'
    $tps = Read-RconPacket $stream
    $tps.text | Set-Content (Join-Path $out 'P40-plugin-windypit-tps.txt') -Encoding UTF8
    "RCON_TPS_LEN=$($tps.text.Length)" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke-result.txt') -Append
    Send-Rcon $stream 3 2 'stop'
    try { $null = Read-RconPacket $stream } catch { }
    $client.Close()
    $exited = $p.WaitForExit(120000)
    "STOP_EXITED=$exited" | Tee-Object -FilePath (Join-Path $out 'P40-plugin-smoke-result.txt') -Append
} else {
    if (-not $p.HasExited) { $p.Kill() }
}
