$ErrorActionPreference = 'Stop'
$round = '20261018-1538-final'
$wp = 'C:\Users\ylxyl\OneDrive\Desktop\WindyPit'
$tp = 'C:\Users\ylxyl\OneDrive\Desktop\ThePitUltimate-release'
$out = Join-Path $wp ".review\windypit-1.8-threaded\dsh-run\$round"
$tpl = Join-Path $wp '.review\windypit-1.8-threaded\dsh-run\20261018-0030\server-smoke-plugin'
$smoke = Join-Path $out 'server-smoke'

if (-not (Test-Path $smoke)) {
    New-Item -ItemType Directory -Force -Path $smoke | Out-Null
    & robocopy $tpl $smoke /E /XD logs /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE" }
}

Copy-Item -Force (Join-Path $wp 'WindSpigot-Server\target\WindSpigot.jar') (Join-Path $smoke 'WindSpigot.jar')
$thePitJar = Get-ChildItem (Join-Path $tp 'base\build\libs') -Filter 'ThePitUltra-*.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item -Force $thePitJar.FullName (Join-Path $smoke 'plugins\ThePitUltra.jar')
Copy-Item -Force (Join-Path $wp '.review\windypit-1.8-threaded\dsh-run\20261018-0030\rcon_client.py') (Join-Path $out 'rcon_client.py')

# Region mode + two region threads so non-arena worlds get distinct owners.
$yml = Get-Content (Join-Path $smoke 'windypit.yml') -Raw
if ($yml -match '(?m)^threading:') {
    $yml = $yml -replace '(?m)^threading:\r?\n(?:  .*\r?\n)*', "threading:`n  mode: region`n  arena-owner: primary`n  region-threads: 2`n  entity-threads: auto`n  compute-threads: auto`n  persistence-threads: bounded`n"
} else {
    $yml += "`nthreading:`n  mode: region`n  arena-owner: primary`n  region-threads: 2`n"
}
if ($yml -notmatch '(?m)^features:') {
    $yml += "features:`n  async-entity-tracking: true`n  async-visual-projection: true`n  async-pathfinding: true`n  async-persistence: true`n  mechanism-pruning: true`n"
}
if ($yml -notmatch '(?m)^rollback:') {
    $yml += "rollback:`n  force-primary-thread-arena: false`n  disable-async-tracking: false`n  disable-region-scheduler: false`n"
}
if ($yml -notmatch '(?m)^pruning:') {
    $yml += "pruning:`n  arena-worlds:`n    - world`n"
}
Set-Content (Join-Path $smoke 'windypit.yml') $yml -Encoding utf8

$java = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$stdout = Join-Path $out 'P65b-smoke-stdout.log'
$stderr = Join-Path $out 'P65b-smoke-stderr.log'
$p = Start-Process -FilePath $java -ArgumentList @(
        '--add-opens','java.base/java.lang=ALL-UNNAMED',
        '--add-opens','java.base/java.net=ALL-UNNAMED',
        '--add-opens','java.base/java.nio=ALL-UNNAMED',
        '--enable-native-access=ALL-UNNAMED',
        '-Xms1G','-Xmx2G',
        '-Dcom.mojang.eula.agree=true',
        '-jar','WindSpigot.jar','--noconsole') `
    -WorkingDirectory $smoke -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
"PID=$($p.Id)" | Set-Content (Join-Path $out 'P65b-smoke-pid.txt')

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

$ok = Wait-Text $stdout 'Done (' 300
"STARTED=$ok EXITED=$($p.HasExited)" | Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt')
if ($ok) {
    Start-Sleep -Seconds 2
    $text = Get-Content $stdout -Raw
    $checks = [ordered]@{
        'DONE' = $text.Contains('Done (')
        'NOT_SCHEDULED_YET' = $text.Contains('Not scheduled yet')
        'NO_CLASS_DEF' = $text.Contains('NoClassDefFoundError')
        'CONCURRENT_MODIFICATION' = $text.Contains('ConcurrentModificationException')
        'DEPENDENCY_SEVERE' = $text.Contains('[Dependencies] SEVERE')
        'PANDA_REPO' = $text.Contains('repo.panda-lang.org')
        'PLUGIN_ENABLED' = $text.Contains('Enabling ThePitUltra')
        'ADAPTER_MODE' = ($text -match 'mode: (WINDYPIT_KERNEL|BUKKIT_FALLBACK)')
        'REGION_MODE' = $text.Contains('threading.mode=REGION') -or $text.Contains('threading.mode=region')
    }
    foreach ($key in $checks.Keys) {
        "$key=$($checks[$key])" | Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt') -Append
    }
    Select-String -Path $stdout -Pattern 'mode: (WINDYPIT_KERNEL|BUKKIT_FALLBACK)' |
        ForEach-Object { $_.Line.Trim() } | Set-Content (Join-Path $out 'P65b-smoke-mode.txt')

    $rcon = Join-Path $out 'rcon_client.py'
    & python $rcon 25578 windypit 'windypit-tps' *> (Join-Path $out 'P65b-smoke-tps.txt')
    "RCON_TPS_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt') -Append
    & python $rcon 25578 windypit 'stop' *> (Join-Path $out 'P65b-smoke-stop.txt')
    "RCON_STOP_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt') -Append
    $exited = $p.WaitForExit(180000)
    "STOP_EXITED=$exited EXIT_CODE=$(if ($p.HasExited) { $p.ExitCode } else { 'RUNNING' })" |
        Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt') -Append

    $after = Get-Content $stdout -Raw
    foreach ($key in @('NOT_SCHEDULED_YET','NO_CLASS_DEF','CONCURRENT_MODIFICATION','DEPENDENCY_SEVERE')) {
        $pattern = switch ($key) {
            'NOT_SCHEDULED_YET' { 'Not scheduled yet' }
            'NO_CLASS_DEF' { 'NoClassDefFoundError' }
            'CONCURRENT_MODIFICATION' { 'ConcurrentModificationException' }
            'DEPENDENCY_SEVERE' { '[Dependencies] SEVERE' }
        }
        "AFTER_STOP_$key=$($after.Contains($pattern))" | Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt') -Append
    }
} else {
    if (-not $p.HasExited) { $p.Kill() }
    "SMOKE_FAILED_NO_DONE" | Tee-Object -FilePath (Join-Path $out 'P65b-smoke-result.txt') -Append
}

