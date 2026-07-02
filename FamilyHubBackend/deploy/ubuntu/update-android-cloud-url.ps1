param(
  [string]$ServerBaseUrl = "https://streamforsoul.com/familyhub/"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..\..\..")
$configPath = Join-Path $repoRoot "FamilyHubApp\app\src\main\java\code\name\monkey\retromusic\SyncTuneConfig.kt"

if (-not (Test-Path -LiteralPath $configPath)) {
  throw "SyncTuneConfig.kt not found at $configPath"
}

if (-not $ServerBaseUrl.EndsWith("/")) {
  $ServerBaseUrl = "$ServerBaseUrl/"
}

$content = Get-Content -LiteralPath $configPath -Raw
$updated = $content -replace 'const val SERVER_BASE_URL = ".*"', "const val SERVER_BASE_URL = `"$ServerBaseUrl`""
Set-Content -LiteralPath $configPath -Value $updated -NoNewline

Write-Host "Updated SERVER_BASE_URL to $ServerBaseUrl"
