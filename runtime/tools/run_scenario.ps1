            pvpClients       = 'clients/pvp-result.json'
            gateSummary      = 'gate-summary.json'
            gateReport       = 'gate-report.md'
        }
    }
    $manifest | ConvertTo-Json -Depth 10 | ForEach-Object {
        [System.IO.File]::WriteAllText((Join-Path $out 'manifest.json'), $_, (New-Object System.Text.UTF8Encoding($false)))
    }
}

try {
    Write-Step "scenario=$Scenario clients=$Clients bots=$Bots useThePit=$UseThePit out=$out"

    if (Test-Path -LiteralPath $serverDir) {
        if (-not $Force) {
            throw "server directory already exists: $serverDir (use -Force to archive and recreate it)"
        }
        $archive = "$serverDir.old-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Move-Item -LiteralPath $serverDir -Destination $archive
        Write-Step "archived previous server directory to $archive"
    }
    New-Item -ItemType Directory -Force -Path $serverDir | Out-Null
    & robocopy $Template $serverDir /E /XD logs /XF WindSpigot.jar ThePitUltra.jar /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "robocopy failed with exit code $LASTEXITCODE"
    }

    Copy-Item -LiteralPath $serverJar -Destination (Join-Path $serverDir 'WindSpigot.jar') -Force

    $script:thePitJar = $null
    if ($UseThePit) {
        $candidates = Get-ChildItem -LiteralPath (Join-Path $ThePitRoot 'base\build\libs') -Filter 'ThePitUltra-*.jar' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending
        if (-not $candidates) {
            throw "no ThePitUltra-*.jar under $ThePitRoot\base\build\libs"
        }
        $script:thePitJar = $candidates[0].FullName
        Copy-Item -LiteralPath $script:thePitJar -Destination (Join-Path $serverDir 'plugins\ThePitUltra.jar') -Force
        Write-Step "installed ThePitUltra: $($script:thePitJar)"
    }
    else {
        $thePitJarPath = Join-Path $serverDir 'plugins\ThePitUltra.jar'
        if (Test-Path -LiteralPath $thePitJarPath) {
            Remove-Item -LiteralPath $thePitJarPath -Force
        }
        $thePitData = Join-Path $serverDir 'plugins\ThePitUltra'
        if (Test-Path -LiteralPath $thePitData) {
            Remove-Item -LiteralPath $thePitData -Recurse -Force
        }
    }

    if (-not $KeepDatabase) {
        $database = Join-Path $serverDir 'plugins\ThePitUltra\thepit.sqlite'
        foreach ($suffix in @('', '-wal', '-shm')) {
            $candidate = "$database$suffix"
            if (Test-Path -LiteralPath $candidate) {
                Remove-Item -LiteralPath $candidate -Force
            }
        }
    }

    Set-PropertiesFile (Join-Path $serverDir 'server.properties') @{
        'server-port'   = "$Port"
        'rcon.port'     = "$RconPort"
        'rcon.password' = $RconPassword
        'enable-rcon'   = 'true'
        'online-mode'   = 'false'
        'max-players'   = "$([Math]::Max(120, $Clients + $Bots + 20))"
    }

    $script:jvmArgs = @(
        '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
        '--add-opens', 'java.base/java.net=ALL-UNNAMED',
        '--add-opens', 'java.base/java.nio=ALL-UNNAMED',
        '--enable-native-access=ALL-UNNAMED',
        "-Xms$HeapMin",
        "-Xmx$HeapMax",
        '-Dcom.mojang.eula.agree=true',
        '-jar', 'WindSpigot.jar',
        '--noconsole'
    )
    $stdout = Join-Path $logsDir 'server-stdout.log'
    $stderr = Join-Path $logsDir 'server-stderr.log'
    $stopLog = Join-Path $logsDir 'server-stop.log'
    Write-Step 'starting server'
    $serverProc = Start-Process -FilePath $java -ArgumentList $script:jvmArgs -WorkingDirectory $serverDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru

    if (-not (Wait-LogText -Path $stdout -Needle 'Done (' -TimeoutSeconds 300 -Process $serverProc)) {
        $tail = if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Tail 20 } else { @() }
        throw "server did not reach 'Done (' within 300s; tail: $($tail -join ' | ')"
    }
    Start-Sleep -Seconds 1
    Write-Step "server ready (pid=$($serverProc.Id))"

    $clientSeconds = $WarmupSeconds + $MeasureSeconds + 15
    if ($Clients -gt 0) {
        $loadOutput = Join-Path $clientsDir 'load-clients.json'
        $arguments = @($loadClients, '--host', '127.0.0.1', '--port', "$Port", '--clients', "$Clients",
            '--seconds', "$clientSeconds", '--output', $loadOutput)
        $clientProcs.Add((Start-Process -FilePath $python -ArgumentList $arguments -PassThru `
            -RedirectStandardOutput (Join-Path $logsDir 'load-clients.log') `
            -RedirectStandardError (Join-Path $logsDir 'load-clients-err.log')))
        Write-Step "started $Clients load client(s)"
    }
    if ($Bots -gt 0) {
        $pvpOutput = Join-Path $clientsDir 'pvp-result.json'
        $arguments = @($pvpClients, '--host', '127.0.0.1', '--port', "$Port", '--clients', "$Bots",
            '--seconds', "$clientSeconds", '--attack-interval-ms', '1000', '--target-policy', 'ring',
            '--output', $pvpOutput)
        $clientProcs.Add((Start-Process -FilePath $python -ArgumentList $arguments -PassThru `
            -RedirectStandardOutput (Join-Path $logsDir 'pvp-clients.log') `
            -RedirectStandardError (Join-Path $logsDir 'pvp-clients-err.log')))
        Write-Step "started $Bots PvP client(s)"
    }

    if ($WarmupSeconds -gt 0) {
        Write-Step "warm-up for $WarmupSeconds s"
        Start-Sleep -Seconds $WarmupSeconds
    }

