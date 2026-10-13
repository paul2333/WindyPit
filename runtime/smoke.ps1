[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$RuntimeRoot = $PSScriptRoot,
    [ValidateSet("g1", "zgc")]
    [string]$Gc,
    [int]$StartupTimeout = 180,
    [string]$LocalPlugin
)

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) {
    Write-Error "Python 3 is required to run the smoke test."
    exit 2
}
$arguments = @((Join-Path $PSScriptRoot "tools\smoke.py"), "--root", (Resolve-Path -LiteralPath $RuntimeRoot).Path, "--startup-timeout", $StartupTimeout)
if ($Gc) { $arguments += @("--gc", $Gc) }
if ($LocalPlugin) { $arguments += @("--local-plugin", (Resolve-Path -LiteralPath $LocalPlugin).Path) }
& $python.Source @arguments
exit $LASTEXITCODE
