[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$RuntimeRoot = $PSScriptRoot,
    [switch]$Offline
)

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) {
    Write-Error "Python 3 is required to verify runtime artifacts."
    exit 2
}

$arguments = @((Join-Path $PSScriptRoot "tools\fetch_artifacts.py"), "--root", (Resolve-Path -LiteralPath $RuntimeRoot).Path)
if ($Offline) { $arguments += "--offline" }
& $python.Source @arguments
exit $LASTEXITCODE
