[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$RuntimeRoot = $PSScriptRoot,
    [ValidateSet("g1", "zgc")]
    [string]$Gc,
    [switch]$Nogui,
    [switch]$DryRun,
    [string]$ServerRoot,
    [string]$HeapMin,
    [string]$HeapMax
)

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) {
    Write-Error "Python 3 is required to start WindyPit."
    exit 2
}

$arguments = @((Join-Path $PSScriptRoot "tools\launcher.py"), "--root", (Resolve-Path -LiteralPath $RuntimeRoot).Path)
if ($Gc) { $arguments += @("--gc", $Gc) }
if ($Nogui) { $arguments += "--nogui" }
if ($DryRun) { $arguments += "--dry-run" }
if ($ServerRoot) { $arguments += @("--server-root", $ServerRoot) }
if ($HeapMin) { $arguments += @("--heap-min", $HeapMin) }
if ($HeapMax) { $arguments += @("--heap-max", $HeapMax) }
& $python.Source @arguments
exit $LASTEXITCODE
