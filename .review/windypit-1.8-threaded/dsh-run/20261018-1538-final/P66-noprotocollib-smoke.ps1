$ErrorActionPreference = 'Stop'
$round = '20261018-1538-final'
$wp = 'C:\Users\ylxyl\OneDrive\Desktop\WindyPit'
$out = Join-Path $wp ".review\windypit-1.8-threaded\dsh-run\$round"
$src = Join-Path $out 'server-smoke'
$smoke = Join-Path $out 'server-smoke-noprotocollib'

if (-not (Test-Path $smoke)) {
    New-Item -ItemType Directory -Force -Path $smoke | Out-Null
    & robocopy $src $smoke /E /XD logs /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE" }
}
# No ProtocolLib at all (jar + extracted plugin dir)
Remove-Item -Force (Join-Path $smoke 'plugins\ProtocolLib.jar') -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force (Join-Path $smoke 'plugins\ProtocolLib') -ErrorAction SilentlyContinue
# Enable auth so the PremiumLogin gate is exercised (it used to NCDFE without ProtocolLib)
$auth = Join-Path $smoke 'plugins\ThePitUltra\auth.yml'
if (Test-Path $auth) {
    $text = Get-Content $auth -Raw
    $text = $text -replace '(?m)^enabled:\s*false\s*$', 'enabled: true'
    Set-Content $auth $text -Encoding utf8
}

$java = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$stdout = Join-Path $out 'P66-noprotocollib-stdout.log'
$stderr = Join-Path $out 'P66-noprotocollib-stderr.log'
$p = Start-Process -FilePath $java -ArgumentList @(
        '--add-opens','java.base/java.lang=ALL-UNNAMED',
        '--add-opens','java.base/java.net=ALL-UNNAMED',
        '--add-opens','java.base/java.nio=ALL-UNNAMED',
        '--enable-native-access=ALL-UNNAMED',
        '-Xms1G','-Xmx2G',
        '-Dcom.mojang.eula.agree=true',
        '-jar','WindSpigot.jar','--noconsole') `
    -WorkingDirectory $smoke -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
"PID=$($p.Id)" | Set-Content (Join-Path $out 'P66-noprotocollib-pid.txt')

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
"STARTED=$ok EXITED=$($p.HasExited)" | Tee-Object -FilePath (Join-Path $out 'P66-noprotocollib-result.txt')
if ($ok) {
    Start-Sleep -Seconds 2
    $text = Get-Content $stdout -Raw
    $checks = [ordered]@{
        'DONE' = $text.Contains('Done (')
        'PLUGIN_ENABLED' = $text.Contains('Enabling ThePitUltra')
        'NO_CLASS_DEF' = $text.Contains('NoClassDefFoundError')
        'PROTOCOL_WARN' = $text.Contains('ProtocolLib is absent') -or $text.Contains('ProtocolLib missing')
        'NPC_GATE_WARN' = $text.Contains('ProtocolLib missing: ')
        'AUTH_GATE_WARN' = $text.Contains('ProtocolLib') -and $text.Contains('auth')
        'SEVERE' = $text.Contains('SEVERE')
    }
    foreach ($key in $checks.Keys) {
        "$key=$($checks[$key])" | Tee-Object -FilePath (Join-Path $out 'P66-noprotocollib-result.txt') -Append
    }
    Select-String -Path $stdout -Pattern 'ProtocolLib' | ForEach-Object { $_.Line.Trim() } |
        Set-Content (Join-Path $out 'P66-noprotocollib-warnings.txt')

    & python (Join-Path $out 'rcon_client.py') 25578 windypit 'stop' *> (Join-Path $out 'P66-noprotocollib-stop.txt')
    "RCON_STOP_EXIT=$LASTEXITCODE" | Tee-Object -FilePath (Join-Path $out 'P66-noprotocollib-result.txt') -Append
    $exited = $p.WaitForExit(180000)
    "STOP_EXITED=$exited" | Tee-Object -FilePath (Join-Path $out 'P66-noprotocollib-result.txt') -Append
} else {
    if (-not $p.HasExited) { $p.Kill() }
    "SMOKE_FAILED_NO_DONE" | Tee-Object -FilePath (Join-Path $out 'P66-noprotocollib-result.txt') -Append
}
