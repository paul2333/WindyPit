# artifact-boundary-scan.ps1 - WindyPit 1.8.8 artifact boundary guard (rev3.2)
# FAIL   = modern Paper/Folia API, non-baseline Adventure, DataComponent, or modern plugin descriptor
# EXEMPT = net/kyori/adventure/key/* pre-existing upstream WindSpigot 1.8.8 baseline subset (W6 review)
# WARN   = legacy upstream io/papermc/paper 1.8.8 code; neutral multi-release markers
# zip directory entries (FullName ends with '/') never FAIL - they carry no code
param([Parameter(Mandatory=$true)][string]$JarPaths)
$forbidden = @(
  'io/papermc/paper/threadedregions',
  'io/papermc/folia',
  'net/kyori/adventure',
  'org/bukkit/inventory/meta/components',
  'paper-plugin.yml'
)
$exempt = @('net/kyori/adventure/key/', 'net/kyori/examination/')
$warnings = @('io/papermc/paper/', 'META-INF/versions')
$fail = 0
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar2 in ($JarPaths -split ',')) {
  if (-not (Test-Path $jar2)) { Write-Output "MISSING $jar2"; $fail = 1; continue }
  $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $jar2))
  try {
    $hits = New-Object System.Collections.Generic.List[string]
    $warns = New-Object System.Collections.Generic.List[string]
    $exempts = New-Object System.Collections.Generic.List[string]
    foreach ($e in $zip.Entries) {
      if ($e.FullName.EndsWith('/')) { continue }
      $isExempt = $false
      foreach ($x in $exempt) { if ($e.FullName -like ("*" + $x + "*")) { $isExempt = $true; $exempts.Add($e.FullName) } }
      if ($isExempt) { continue }
      foreach ($f in $forbidden) { if ($e.FullName -like ("*" + $f + "*")) { $hits.Add("$f <- $($e.FullName)") } }
      foreach ($w in $warnings) { if ($e.FullName -like ("*" + $w + "*")) { $warns.Add("$w <- $($e.FullName)") } }
    }
    if ($hits.Count -gt 0) { Write-Output "VIOLATION $jar2"; $hits | Select-Object -First 20 | ForEach-Object { Write-Output "  FAIL $_" }; $fail = 1 }
    else { Write-Output "CLEAN $jar2 ($($zip.Entries.Count) entries)" }
    if ($exempts.Count -gt 0) { Write-Output "  EXEMPT(baseline) $jar2 adventure-key + examination baseline subset: $($exempts.Count) class entries (upstream 1.8.8, W6 review)" }
    if ($warns.Count -gt 0) { Write-Output "  WARN $jar2 legacy/neutral: $($warns.Count) entries" }
  } finally { $zip.Dispose() }
}
exit $fail

